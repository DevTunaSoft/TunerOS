#!/usr/bin/env python3
"""QA-04 bounded SQLite bridge: real sqlite3 behind the JVM harness protocol.

The protocol is one request per line, fields separated by ASCII US (0x1f),
values encoded as n (null), s<base64> (text) or i<decimal> (integer).
Only scratch databases under QA04_DB_DIR are opened. No provider data.
"""
import base64
import os
import re
import sqlite3
import sys

SEP = "\x1f"
GS = "\x1d"
ROOT = os.environ.get("QA04_DB_DIR", "")
if not ROOT:
    raise SystemExit("QA04_DB_DIR must name an existing scratch directory")
ROOT = os.path.abspath(ROOT)
if not os.path.isdir(ROOT):
    raise SystemExit("QA04_DB_DIR must name an existing scratch directory")
DROP_QUERY_ROWS = os.environ.get("QA04_FAULT_QUERY_DROP") == "true"
CONNECTIONS = {}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]+$")


def path_for(name):
    if not SAFE_NAME.fullmatch(name):
        raise ValueError("unsafe scratch database name")
    return os.path.join(ROOT, name)


def connection(name):
    found = CONNECTIONS.get(name)
    if found is None:
        found = sqlite3.connect(path_for(name), timeout=10)
        CONNECTIONS[name] = found
    return found


def encode(value):
    if value is None:
        return "n"
    if isinstance(value, str):
        return "s" + base64.b64encode(value.encode("utf-8")).decode("ascii")
    if isinstance(value, (int, float)):
        return "i" + str(value)
    return "s" + base64.b64encode(bytes(value)).decode("ascii")


def decode(token):
    if token == "n":
        return None
    if token.startswith("s"):
        return base64.b64decode(token[1:]).decode("utf-8")
    if token.startswith("i"):
        return int(token[1:])
    raise ValueError("unknown QA value token")


def reply(fields):
    sys.stdout.write(SEP.join(str(field) for field in fields) + "\n")
    sys.stdout.flush()


def handle(parts):
    op = parts[0]
    if op == "shutdown":
        return False
    if op == "exists":
        reply(["YES" if os.path.isfile(path_for(parts[1])) else "NO"])
        return True
    if op == "open":
        connection(parts[1])
        reply(["OK"])
        return True
    if op == "close":
        found = CONNECTIONS.pop(parts[1], None)
        if found is not None:
            found.commit()
            found.close()
        reply(["OK"])
        return True
    if op == "version":
        row = connection(parts[1]).execute("PRAGMA user_version").fetchone()
        reply([str(row[0])])
        return True
    if op == "setversion":
        version = int(parts[2])
        if not 0 <= version < 1_000_000:
            raise ValueError("out-of-range QA schema version")
        connection(parts[1]).execute("PRAGMA user_version = %d" % version)
        connection(parts[1]).commit()
        reply(["OK"])
        return True
    if op == "exec":
        sql = base64.b64decode(parts[2]).decode("utf-8")
        connection(parts[1]).execute(sql)
        connection(parts[1]).commit()
        reply(["OK"])
        return True
    if op in ("insert", "update"):
        table = parts[2]
        where = base64.b64decode(parts[3]).decode("utf-8") if op == "update" else ""
        db = connection(parts[1])
        if op == "insert":
            pairs = list(zip(parts[3::2], parts[4::2]))
            columns = ", ".join(name for name, _ in pairs)
            slots = ", ".join("?" for _ in pairs)
            cursor = db.execute(
                "INSERT OR IGNORE INTO %s (%s) VALUES (%s)" % (table, columns, slots),
                [decode(value) for _, value in pairs],
            )
            db.commit()
            reply(["-1" if cursor.rowcount == 0 else str(cursor.lastrowid)])
            return True
        pair_count = int(parts[4])
        pairs = list(zip(parts[5:5 + 2 * pair_count:2], parts[6:6 + 2 * pair_count:2]))
        argument_offset = 5 + 2 * pair_count
        arguments = [decode(item) for item in parts[argument_offset + 1:]]
        assignments = ", ".join("%s = ?" % name for name, _ in pairs)
        sql = "UPDATE %s SET %s" % (table, assignments)
        if where:
            sql += " WHERE " + where
        cursor = db.execute(sql, [decode(value) for _, value in pairs] + arguments)
        db.commit()
        reply([str(cursor.rowcount)])
        return True
    if op == "delete":
        table = parts[2]
        where = base64.b64decode(parts[3]).decode("utf-8")
        arguments = [decode(item) for item in parts[5:]]
        sql = "DELETE FROM %s" % table
        if where:
            sql += " WHERE " + where
        cursor = connection(parts[1]).execute(sql, arguments)
        connection(parts[1]).commit()
        reply([str(cursor.rowcount)])
        return True
    if op == "query":
        sql = base64.b64decode(parts[2]).decode("utf-8")
        arguments = [decode(item) for item in parts[4:]]
        cursor = connection(parts[1]).execute(sql, arguments)
        columns = [item[0] for item in cursor.description]
        rows = cursor.fetchall()
        # Negative control: a silent row loss during paged reads. Single-row
        # lookups are left alone so the large round-trip/rearm tests decide.
        if DROP_QUERY_ROWS and len(rows) > 1:
            rows = [row for index, row in enumerate(rows) if index % 37 != 0]
        fields = ["ROWS", str(len(rows))] + columns + ["ENDCOLS"]
        fields += [GS.join(encode(value) for value in row) for row in rows]
        reply(fields)
        return True
    raise ValueError("unknown QA bridge operation: " + op)


for line in sys.stdin:
    parts = line.rstrip("\n").split(SEP)
    if not handle(parts):
        break
for open_connection in CONNECTIONS.values():
    open_connection.close()
