from flask import Flask, request, jsonify
from flask_cors import CORS
import sqlite3
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "expenses.db"

app = Flask(__name__)
CORS(app)

def get_conn():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_conn()
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS expenses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item TEXT NOT NULL,
            place TEXT NOT NULL,
            amount REAL,
            raw_text TEXT,
            timestamp INTEGER NOT NULL
        )
        """
    )
    conn.commit()
    conn.close()

@app.post("/expenses")
def create_expense():
    data = request.get_json(force=True)
    item = (data.get("item") or "").strip()
    place = (data.get("place") or "").strip()
    amount = data.get("amount")
    raw_text = data.get("rawText", "")
    timestamp = int(data.get("timestamp") or 0)

    if not item or not place or not timestamp:
        return jsonify({"error": "item, place y timestamp son obligatorios"}), 400

    conn = get_conn()
    cur = conn.execute(
        "INSERT INTO expenses (item, place, amount, raw_text, timestamp) VALUES (?, ?, ?, ?, ?)",
        (item, place, amount, raw_text, timestamp),
    )
    conn.commit()
    new_id = cur.lastrowid
    conn.close()

    return jsonify({"id": new_id, "status": "ok"}), 201

@app.get("/expenses")
def list_expenses():
    conn = get_conn()
    rows = conn.execute(
        "SELECT id, item, place, amount, raw_text, timestamp FROM expenses ORDER BY id DESC"
    ).fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])

if __name__ == "__main__":
    init_db()
    print("⚠ app.py (Flask/SQLite) está en legado. Backend activo recomendado: app.php + MySQL.")
    print("Ejemplo: php -S 0.0.0.0:8080 app.php")
    app.run(host="0.0.0.0", port=8080, debug=True)
