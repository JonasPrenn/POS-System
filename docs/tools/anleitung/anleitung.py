#!/usr/bin/env python3
"""Die Anleitung bauen: ein Wegwerf-Server mit erfundenem Verein, Screenshots, PDF.

    python3 docs/tools/anleitung/anleitung.py alles   # alles unten der Reihe nach, am Ende aufräumen
    python3 docs/tools/anleitung/anleitung.py start   # Server bauen, PostgreSQL in Docker, Server starten, Demodaten
    python3 docs/tools/anleitung/anleitung.py web     # Bilder der Verwaltung, der Systemverwaltung und des Mitglieder-Deckels
    python3 docs/tools/anleitung/anleitung.py app     # Bilder der App (läuft ein Android-Emulator? siehe app.py)
    python3 docs/tools/anleitung/anleitung.py pdf     # docs/anleitung/anleitung.html → docs/Anleitung-VereinsDeckel.pdf
    python3 docs/tools/anleitung/anleitung.py stop    # Server und Datenbank weg

Hinter `alles`, `web` und `app` dürfen Bildnamen stehen (ohne .jpg): Dann wird nur dieses Bild neu
gemacht, alle anderen bleiben Byte für Byte, wie sie sind —

    python3 docs/tools/anleitung/anleitung.py alles app-bar verwaltung-kasse

Das ist der Normalfall bei einem Commit: Jedes neu gemachte Bild trägt neue Uhrzeiten und landet
als neue Datei in der Geschichte des Repos, das auch der Server bei jedem Update holt. Neu
fotografiert wird deshalb, was sich sichtbar geändert hat, nicht alles.

Braucht: Docker, Google Chrome, für `app` einen laufenden Android-Emulator. Der Server läuft nur
auf 127.0.0.1 (der Emulator erreicht ihn als 10.0.2.2); alle Zugänge sind Demo-Werte und gelten
nur für diesen Wegwerf-Server. Echte Daten kommen nie hinein.
"""
import http.cookiejar
import json
import os
import random
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DOCS = os.path.join(ROOT, "docs", "anleitung")
BILDER = os.path.join(DOCS, "bilder")
PDF = os.path.join(ROOT, "docs", "Anleitung-VereinsDeckel.pdf")
STATE = os.path.join(tempfile.gettempdir(), "vereinsdeckel-anleitung")

PORT = 18090
DB_PORT = 55497
DB = "vd-anleitung-db"
BASE = f"http://127.0.0.1:{PORT}"
TOKEN = "anleitung-schluessel-1234"   # PAIRING_ADMIN_TOKEN des Wegwerf-Servers
PASSWORT = "musterbude-2026"           # alle Demo-Zugänge
KUERZEL = "musterbude"
ZONE = ZoneInfo("Europe/Vienna")
JAVA_HOME = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"

sys.path.insert(0, HERE)

NUR = set()  # Bilder, die neu gemacht werden (Namen ohne .jpg); leer heißt alle


def log(text):
    print(f"· {text}", flush=True)


def referenced_images():
    """Die Bilder, die anleitung.html zeigt — in der Reihenfolge, in der sie dort stehen."""
    html = open(os.path.join(DOCS, "anleitung.html"), encoding="utf-8").read()
    return list(dict.fromkeys(re.findall(r'src="bilder/([\w-]+)\.jpg"', html)))


# ------------------------------------------------------------------ Werkzeuge

def gradle(*tasks):
    env = dict(os.environ)
    if os.path.isdir(JAVA_HOME):
        env["JAVA_HOME"] = JAVA_HOME
    subprocess.run([os.path.join(ROOT, "gradlew"), "-q", *tasks], cwd=ROOT, env=env, check=True)


def sql(statements, database="vereinsdeckel"):
    subprocess.run(["docker", "exec", "-i", DB, "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", "vereinsdeckel", "-d", database],
                   input=statements, text=True, check=True, stdout=subprocess.DEVNULL)


class Browser:
    """HTTP mit Cookies, für die Formulare der Verwaltung."""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def get(self, path):
        with self.opener.open(BASE + path, timeout=60) as r:
            return r.read().decode()

    def post(self, path, fields):
        data = urllib.parse.urlencode(fields).encode()
        with self.opener.open(urllib.request.Request(BASE + path, data=data), timeout=60) as r:
            return r.geturl(), r.read().decode()

    def form(self, page, path, fields):
        """Holt [page] für das Geheimnis der Sitzung und schickt dann das Formular an [path]."""
        csrf = re.search(r'name="_csrf" value="([^"]+)"', self.get(page)).group(1)
        url, body = self.post(path, {"_csrf": csrf, **fields})
        if "fehler=" in url:
            raise SystemExit(f"{path}: {urllib.parse.unquote(url.split('fehler=')[1])}")
        return url, body


def api(path, body, token=TOKEN):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST", headers=headers)
    with urllib.request.urlopen(request, timeout=60) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


def new_id():
    return str(uuid.uuid4())


def iso(moment):
    return moment.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


# ------------------------------------------------------------------ Server

def start():
    os.makedirs(STATE, exist_ok=True)
    stop(quiet=True)
    log("Server bauen (Gradle) …")
    gradle(":server:installDist")
    log("PostgreSQL in Docker …")
    subprocess.run(["docker", "run", "-d", "--rm", "--name", DB, "-e", "POSTGRES_USER=vereinsdeckel", "-e", "POSTGRES_PASSWORD=anleitung",
                    "-e", "POSTGRES_DB=vereinsdeckel", "-p", f"127.0.0.1:{DB_PORT}:5432", "postgres:16"], check=True, stdout=subprocess.DEVNULL)
    for _ in range(60):
        if subprocess.run(["docker", "exec", DB, "pg_isready", "-U", "vereinsdeckel"], stdout=subprocess.DEVNULL).returncode == 0:
            break
        time.sleep(1)
    time.sleep(2)
    env = dict(os.environ, DATABASE_URL=f"postgres://vereinsdeckel:anleitung@127.0.0.1:{DB_PORT}/vereinsdeckel", PAIRING_ADMIN_TOKEN=TOKEN,
               WEB_INSECURE_COOKIES="true", PORT=str(PORT), HOST="127.0.0.1", MEDIA_DIR=os.path.join(STATE, "media"), VEREIN_ZONE="Europe/Vienna")
    if os.path.isdir(JAVA_HOME):
        env["JAVA_HOME"] = JAVA_HOME
    logfile = open(os.path.join(STATE, "server.log"), "w")
    proc = subprocess.Popen([os.path.join(ROOT, "server", "build", "install", "vereinsdeckel-server", "bin", "vereinsdeckel-server")],
                            env=env, stdout=logfile, stderr=subprocess.STDOUT, start_new_session=True)
    with open(os.path.join(STATE, "server.pid"), "w") as f:
        f.write(str(proc.pid))
    for _ in range(90):
        try:
            with urllib.request.urlopen(BASE + "/v1/health", timeout=2) as r:
                if r.status == 200:
                    break
        except Exception:
            time.sleep(1)
    else:
        raise SystemExit("Der Server startet nicht — siehe " + os.path.join(STATE, "server.log"))
    log(f"Server läuft auf {BASE}")
    demo()


def stop(quiet=False):
    pidfile = os.path.join(STATE, "server.pid")
    if os.path.exists(pidfile):
        try:
            os.killpg(int(open(pidfile).read()), 15)
        except Exception:
            pass
        os.remove(pidfile)
    subprocess.run(["docker", "stop", DB], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not quiet:
        log("Server und Datenbank beendet")


# ------------------------------------------------------------------ Demodaten

MEMBERS = [  # erfunden — kein echtes Mitglied, nirgends
    ("Lukas Hofer", "Sokrates", "Aktive"), ("Maria Bauer", "", "Aktive"), ("Georg Lechner", "Nestor", "Alte Herren"),
    ("Anna Berger", "Athene", "Aktive"), ("Peter Gruber", "Ikarus", "Aktive"), ("Thomas Wallner", "", "Alte Herren"),
    ("Sophie Mair", "Iris", "Aktive"), ("Florian Egger", "", "Gäste"), ("Johanna Pichler", "Klio", "Aktive"),
    ("Martin Huber", "Archimedes", "Alte Herren"), ("Elias Wimmer", "", "Aktive"),
]

# Name, Preis, Kategorie, Varianten (Name, Preis, Glas in Litern), Lagerartikel, Menge je Einheit
PRODUCTS = [
    ("Helles", 4.20, "Getränke", [("0,5 l", 4.20, 0.5), ("0,3 l", 3.20, 0.3)], "Helles", 1.0),
    ("Zwickl 0,5 l", 4.60, "Getränke", [], "Zwickl", 0.5),
    ("Radler 0,5 l", 4.00, "Getränke", [], "Helles", 0.25),
    ("Weißer Spritzer", 3.00, "Getränke", [], None, 0),
    ("Almdudler 0,35 l", 2.80, "Getränke", [], "Almdudler", 1),
    ("Cola 0,33 l", 2.80, "Getränke", [], "Cola", 1),
    ("Mineral 0,5 l", 2.00, "Getränke", [], "Mineral", 1),
    ("Bratwurst mit Senf", 4.50, "Küche", [], "Bratwurst", 1),
    ("Leberkässemmel", 3.80, "Küche", [], "Leberkäse", 1),
    ("Chips", 2.00, "Küche", [], "Chips", 1),
]

# Name, Einheit, Art, Mindestbestand, Gebinde (Bezeichnung, Liter, Ausbeute)
STOCK = [
    ("Helles", "l", "CONTAINER", 50, ("Fass 50 l", 50, 48)), ("Zwickl", "l", "CONTAINER", 25, ("Fass 30 l", 30, 28.5)),
    ("Almdudler", "Fl", "SIMPLE", 24, None), ("Cola", "Fl", "SIMPLE", 24, None), ("Mineral", "Fl", "SIMPLE", 24, None),
    ("Bratwurst", "Stk", "SIMPLE", 20, None), ("Leberkäse", "Stk", "SIMPLE", 10, None), ("Chips", "Pkg", "SIMPLE", 12, None),
]


class Demo:
    """Ein Verein, wie er nach ein paar Wochen Betrieb aussieht — erfunden, aber stimmig."""

    def __init__(self):
        self.rng = random.Random(20261001)
        self.cash_lines = []  # (Zeitpunkt, Betrag) aller Bareinnahmen, für den Zählbetrag der Schicht
        self.now = datetime.now(ZONE)
        self.ops = []
        self.member_ids = {}
        self.category_ids = {}
        self.product_ids = {}
        self.variant_ids = {}
        self.stock_ids = {}
        self.container_ids = {}

    def op(self, entity, row, op="insert"):
        self.ops.append({"client_change_id": new_id(), "entity": entity, "op": op, "row": row})

    def push(self, token):
        while self.ops:
            batch, self.ops = self.ops[:400], self.ops[400:]
            answer = api("/v1/sync/push", {"operations": batch}, token=token)
            bad = [r for r in answer["results"] if r["status"] not in ("applied", "ignored_stale")]
            if bad:
                raise SystemExit(f"Push abgelehnt: {bad[:3]}")

    def evening(self, days_ago, hour=None):
        day = (self.now - timedelta(days=days_ago)).date()
        h = hour if hour is not None else self.rng.choice([18, 19, 19, 20, 20, 21, 21, 22, 23])
        return datetime(day.year, day.month, day.day, h, self.rng.randrange(60), self.rng.randrange(60), tzinfo=ZONE)

    def master_data(self):
        limits = {"Aktive": "-50.00", "Alte Herren": "-150.00", "Gäste": "0.00"}
        for name, limit in limits.items():
            self.category_ids[name] = new_id()
            self.op("member_categories", {"id": self.category_ids[name], "name": name, "negative_balance_limit": limit})
        for name, nick, category in MEMBERS:
            self.member_ids[name] = new_id()
            row = {"id": self.member_ids[name], "name": name, "nickname": nick, "category_id": self.category_ids[category]}
            if name == "Elias Wimmer":
                row["blocked_reason"] = "Bierrechnung Juni offen"
            self.op("members", row)
        for name, unit, kind, minimum, container in STOCK:
            self.stock_ids[name] = new_id()
            self.op("stock_items", {"id": self.stock_ids[name], "name": name, "unit": unit, "tracking": kind, "min_level": float(minimum)})
            if container:
                self.container_ids[name] = new_id()
                self.op("container_types", {"id": self.container_ids[name], "stock_item_id": self.stock_ids[name], "label": container[0],
                                            "nominal_size": float(container[1]), "initial_yield_estimate": float(container[2])})
        for name, price, category, variants, stock, per_unit in PRODUCTS:
            pid = new_id()
            self.product_ids[name] = pid
            self.op("products", {"id": pid, "name": name, "price": f"{price:.2f}", "category": category, "has_variants": bool(variants), "serving_size": 1.0})
            for vname, vprice, serving in variants:
                vid = new_id()
                self.variant_ids[(name, vname)] = (vid, vprice, serving)
                self.op("product_variants", {"id": vid, "product_id": pid, "name": vname, "price": f"{vprice:.2f}", "serving_size": serving})
            if stock:
                self.op("product_components", {"id": new_id(), "product_id": pid, "stock_item_id": self.stock_ids[stock], "quantity_per_unit": float(per_unit)})

    def deliveries(self):
        def delivery(days, supplier, total, note, lines):
            did = new_id()
            at = self.evening(days, 17)
            self.op("deliveries", {"id": did, "supplier": supplier, "receipt_total": f"{total:.2f}", "note": note, "occurred_at": iso(at)})
            for item, qty, label, cost in lines:
                row = {"id": new_id(), "stock_item_id": self.stock_ids[item], "item_name": item, "quantity": float(qty), "unit_label": label,
                       "total_cost": f"{cost:.2f}", "source": "MANUAL", "occurred_at": iso(at), "delivery_id": did}
                if item in self.container_ids:
                    row["container_type_id"] = self.container_ids[item]
                self.op("stock_entries", row)
        delivery(16, "Brauerei Zipf", 486.40, "Rechnung 2026-0912", [("Helles", 4, "Fass 50 l", 380.00), ("Zwickl", 1, "Fass 30 l", 106.40)])
        delivery(9, "Getränkehandel Mayr", 141.60, "Lieferschein 4471", [("Almdudler", 48, "Fl", 48.00), ("Cola", 48, "Fl", 45.60), ("Mineral", 48, "Fl", 48.00)])
        delivery(3, "Metro", 118.70, "Einkauf fürs Wochenende", [("Bratwurst", 40, "Stk", 56.00), ("Leberkäse", 20, "Stk", 38.70), ("Chips", 24, "Pkg", 24.00)])
        # Ein Fass Helles und das Zwickl sind angestochen.
        self.helles_tap = self.evening(6, 18)
        self.op("tapped_containers", {"id": new_id(), "container_type_id": self.container_ids["Helles"], "opened_at": iso(self.helles_tap)})
        self.op("tapped_containers", {"id": new_id(), "container_type_id": self.container_ids["Zwickl"], "opened_at": iso(self.evening(5, 18))})

    def sales(self):
        """Zwei Wochen Thekenbetrieb: Bier vor allem, Deckel vor allem, ein paar Aufladungen."""
        names = list(self.member_ids)
        weights = [("Helles", "0,5 l", 30), ("Helles", "0,3 l", 6), ("Zwickl 0,5 l", None, 8), ("Radler 0,5 l", None, 6), ("Weißer Spritzer", None, 5),
                   ("Almdudler 0,35 l", None, 5), ("Cola 0,33 l", None, 4), ("Mineral 0,5 l", None, 5), ("Bratwurst mit Senf", None, 4),
                   ("Leberkässemmel", None, 3), ("Chips", None, 3)]
        pool = self.pool = [(p, v) for p, v, w in weights for _ in range(w)]
        for days in range(13, 0, -1):
            if (self.now - timedelta(days=days)).weekday() in (0, 6) and days > 2:
                continue  # Sonntag und Montag ist zu
            for _ in range(self.rng.randint(7, 14)):
                at = self.evening(days)
                group = new_id()
                pay = self.rng.choices(["MEMBER_BALANCE", "CASH", "CARD"], [6, 3, 1])[0]
                member = self.rng.choice(names[:-1]) if pay == "MEMBER_BALANCE" else None
                for _ in range(self.rng.choice([1, 1, 2])):
                    product, variant = self.rng.choice(pool)
                    qty = self.rng.choice([1, 1, 1, 2])
                    self.sale_line(group, product, variant, qty, pay, member, at)
        # Heute Frühschoppen, bis vor einer Viertelstunde — damit „Umsatz heute“ etwas zeigt.
        start, end = self.now.replace(hour=10, minute=30, second=0, microsecond=0), self.now - timedelta(minutes=15)
        if end > start:
            for _ in range(6):
                at = start + (end - start) * self.rng.random()
                pay = self.rng.choices(["MEMBER_BALANCE", "CASH", "CARD"], [5, 3, 2])[0]
                member = self.rng.choice(names[:-1]) if pay == "MEMBER_BALANCE" else None
                product, variant = self.rng.choice(pool)
                self.sale_line(new_id(), product, variant, self.rng.choice([1, 2]), pay, member, at)
        # Aufladungen: bar an der Theke, Überweisung, eine online.
        for days, name, amount, kind in [(12, "Lukas Hofer", 50, "CASH"), (10, "Anna Berger", 30, "CARD"), (8, "Georg Lechner", 100, "BANK"),
                                         (6, "Sophie Mair", 40, "CASH"), (4, "Maria Bauer", 20, "CARD"), (2, "Johanna Pichler", 50, "BANK")]:
            self.top_up(name, amount, kind, self.evening(days, 19))

    def sale_line(self, group, product, variant, qty, pay, member, at):
        pid = self.product_ids[product]
        spec = next(p for p in PRODUCTS if p[0] == product)
        if variant:
            _, price, serving = self.variant_ids[(product, variant)]
            label = f"{product} ({variant})"
        else:
            price, serving, label = spec[1], 1.0, product
        tid = new_id()
        row = {"id": tid, "transaction_group_id": group, "product_ref": pid, "product_name": label, "product_category": spec[2],
               "price": f"{price:.2f}", "quantity": qty, "payment_type": pay, "occurred_at": iso(at)}
        if member:
            row["member_id"] = self.member_ids[member]
            row["member_name"] = member
        self.op("transactions", row)
        if pay == "CASH":
            self.cash_lines.append((at, price * qty))
        stock, per_unit = spec[4], spec[5]
        if stock and (stock not in self.container_ids or at > self.helles_tap):
            volume = per_unit * serving * qty if stock in self.container_ids else per_unit * qty
            self.op("stock_draws", {"id": new_id(), "stock_item_id": self.stock_ids[stock], "transaction_id": tid, "volume": float(volume), "occurred_at": iso(at)})

    def top_up(self, name, amount, kind, at):
        if kind == "CASH":
            self.cash_lines.append((at, amount))
        self.op("transactions", {"id": new_id(), "transaction_group_id": new_id(), "member_id": self.member_ids[name], "member_name": name,
                                 "product_ref": "00000000-0000-0000-0000-000000000000", "product_name": "Guthabenaufladung", "product_category": "Guthaben",
                                 "price": f"{amount:.2f}", "quantity": 1, "payment_type": kind, "occurred_at": iso(at)})

    def cash(self):
        """Gestern eine Schicht mit Barkasse, gezählt und geschlossen; dabei 100 € zur Bank."""
        opened, closed = self.evening(1, 18), self.evening(1, 23)
        # Was an diesem Abend bar über die Theke ging — die Zufallsverkäufe allein lassen die Lade sonst leer.
        for _ in range(12):
            product, variant = self.rng.choice(self.pool)
            self.sale_line(new_id(), product, variant, self.rng.choice([1, 2, 2, 3]), "CASH", None,
                           opened + (closed - opened) * self.rng.uniform(0.05, 0.85))
        sid = new_id()
        self.op("cash_sessions", {"id": sid, "device_label": "Theke 2", "opened_at": iso(opened), "opened_by": "Peter Gruber", "opening_count": "150.00"})
        self.op("cash_movements", {"id": new_id(), "session_id": sid, "kind": "WITHDRAWAL", "amount": "100.00", "reason": "zur Bank",
                                   "by_name": "Peter Gruber", "occurred_at": iso(closed - timedelta(minutes=20))})
        self.cash_session = (sid, opened, closed)


def demo():
    log("Demodaten: Verwaltung einrichten …")
    lena = Browser()
    lena.post("/verwaltung/einrichten", {"schluessel": TOKEN, "name": "Lena Obfrau", "login": "lena", "passwort": PASSWORT, "passwort2": PASSWORT})
    lena.post("/verwaltung/anmelden", {"login": "lena", "passwort": PASSWORT})
    s = "/verwaltung/einstellungen"
    lena.form(s, s, {"name": "Verbindung Musterbude", "farbe": "#8E1538", "monat": "1",
                     "anschrift": "Verbindung Musterbude\nMustergasse 12\n6020 Innsbruck"})
    lena.form(s, s, {"teil": "bank", "inhaber": "Verbindung Musterbude", "iban": "AT61 1904 3002 3457 3201", "bic": "",
                     "text": "Fragen zur Rechnung: kassier@musterbude.example"})
    lena.form(s, s, {"teil": "smtp", "host": "smtp.musterbude.example", "port": "587", "benutzer": "kassier@musterbude.example",
                     "passwort": "nur-demo", "absender": "kassier@musterbude.example", "starttls": "1"})
    lena.form(s, s, {"teil": "kuerzel", "kuerzel": KUERZEL})
    for name, login, role, until in [("Karl Kassa", "karl", "KASSIER", ""), ("Simon Senior", "simon", "VORSTAND", ""),
                                     ("Bernd Bude", "bernd", "BUDENWART", ""), ("Petra Prüfer", "petra", "PRUEFER", str(date.today() + timedelta(days=60)))]:
        lena.form("/verwaltung/benutzer", "/verwaltung/benutzer", {"name": name, "login": login, "rolle": role, "passwort": PASSWORT, "bis": until})

    log("Demodaten: eine zweite Theke bucht zwei Wochen Betrieb …")
    code = api("/v1/admin/pairing-codes", {})["code"]
    theke = api("/v1/devices/register", {"pairing_code": code, "label": "Theke 2", "platform": "android"}, token=None)["token"]
    d = Demo()
    d.master_data()
    d.push(theke)
    d.deliveries()
    d.sales()
    d.cash()
    d.push(theke)

    log("Demodaten: Profile, Abrechnung, Online-Aufladung …")
    for i, (name, _, _) in enumerate(MEMBERS):
        first, last = name.lower().split(" ", 1)
        lena.form(f"/verwaltung/mitglieder?m={d.member_ids[name]}", f"/verwaltung/mitglieder/{d.member_ids[name]}/profil",
                  {"nummer": str(101 + i), "email": f"{first}.{last}@example.at", "anschrift": f"{name}\nMusterweg {i + 1}\n6020 Innsbruck",
                   "einwilligung": "1", "notizen": ""})
    # Die Bierrechnung für die letzten zwei Wochen, bis gestern.
    today = d.now.date()
    months = ["Jänner", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"]
    until = today - timedelta(days=1)
    lena.form("/verwaltung/abrechnung", "/verwaltung/abrechnung/lauf",
              {"name": f"Bierrechnung {months[until.month - 1]}", "von": str(today - timedelta(days=21)),
               "stichtag": str(until), "ziel": str(until + timedelta(days=14)), "schwelle": "-5", "zusatz": "", "zusatzbetrag": ""})
    # Gestern geschlossen, mit gezählter Lade (Soll steht in der Verwaltung, die Differenz auch).
    # Gezählt wird, was in der Lade sein müsste, minus zwei Euro — so steht im Kassenbuch eine kleine Differenz mit Grund.
    sid, opened, closed = d.cash_session
    cash_in = sum(amount for at, amount in d.cash_lines if opened <= at < closed)
    counted = 150 + cash_in - 100 - 2
    d.op("cash_sessions", {"id": sid, "device_label": "Theke 2", "opened_at": iso(opened), "opened_by": "Peter Gruber", "opening_count": "150.00",
                           "closed_at": iso(closed), "closed_by": "Peter Gruber", "closing_count": f"{counted:.2f}",
                           "note": "Zwei Euro Wechselgeld fehlen"}, op="update")
    d.push(theke)
    # Online aufladen eingeschaltet; Schlüssel und Händlercode sind Demo-Werte, SumUp wird nie gefragt.
    sql("INSERT INTO settings (key, value) VALUES ('online_enabled', '1'), ('sumup_api_key', 'sup_sk_nur_demo'), ('sumup_merchant_code', 'MCDEMO01'),"
        " ('online_presets', '10,20,50'), ('online_min', '5'), ('online_max', '200'), ('sumup_methods', 'apple_pay,google_pay')"
        " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;")
    maria = d.member_ids["Maria Bauer"]
    paid, pending = new_id(), new_id()
    lena.form(f"/verwaltung/mitglieder?m={maria}", f"/verwaltung/mitglieder/{maria}/buchung",
              {"buchung": paid, "art": "aufladung", "zahlart": "CARD", "betrag": "20", "notiz": "Online-Aufladung"})
    sql(f"INSERT INTO online_topups (id, member_id, amount, provider, checkout_id, status, email, detail, created_at, settled_at) VALUES"
        f" ('{paid}', '{maria}', 20.00, 'sumup', 'demo-1', 'PAID', 'maria.bauer@example.at', 'SumUp TX7Q2K', now() - interval '3 minutes', now() - interval '2 minutes'),"
        f" ('{pending}', '{maria}', 20.00, 'sumup', 'demo-2', 'PENDING', 'maria.bauer@example.at', '', now(), NULL);"
        f"UPDATE online_topups SET pay_url = 'https://checkout.sumup.com/pay/demo' WHERE id = '{pending}';")

    log("Demodaten: Systemverwaltung …")
    haupt = Browser()
    haupt.post("/verwaltung/system/einrichten", {"schluessel": TOKEN, "name": "Hanna Hauptadmin", "login": "hanna", "passwort": PASSWORT, "passwort2": PASSWORT})
    haupt.post("/verwaltung/anmelden", {"login": "hanna@system", "passwort": PASSWORT})
    sys_page = "/verwaltung/system"
    haupt.form(sys_page, sys_page, {"teil": "verein", "name": "AV Beispiel", "kuerzel": "beispiel", "admin_name": "Otto Obmann",
                                    "admin_login": "otto", "passwort": PASSWORT, "passwort2": PASSWORT})
    haupt.form(sys_page, sys_page, {"teil": "adresse", "adresse": "https://deckel.musterbude.example"})

    with open(os.path.join(STATE, "demo.json"), "w") as f:
        json.dump({"maria": maria, "paid": paid, "pending": pending, "lukas": d.member_ids["Lukas Hofer"]}, f)
    log("Demodaten fertig")


# ------------------------------------------------------------------ Bilder im Browser

def web():
    from chrome import Chrome
    os.makedirs(BILDER, exist_ok=True)
    ids = json.load(open(os.path.join(STATE, "demo.json")))
    c = Chrome()

    def snap(name, **kw):
        if NUR and name[:-4] not in NUR:
            return
        c.shot(os.path.join(BILDER, name), **kw)
        log(f"Bild {name}")

    def shot(name, path, **kw):
        c.go(BASE + path)
        snap(name, **kw)

    def login(user, target="/verwaltung"):
        c.page("Network.clearBrowserCookies")
        c.go(BASE + "/verwaltung/anmelden")
        c.submit({"login": user, "passwort": PASSWORT})
        if target:
            c.go(BASE + target)

    try:
        c.size(1280, 820)
        shot("verwaltung-anmelden.jpg", "/verwaltung/anmelden", selector=".gate-card", pad=28)
        login("lena")
        for name, path in [("verwaltung-uebersicht.jpg", "/verwaltung"), ("verwaltung-mitglieder.jpg", "/verwaltung/mitglieder"),
                           ("verwaltung-mitglied.jpg", f"/verwaltung/mitglieder?m={ids['lukas']}"), ("verwaltung-abrechnung.jpg", "/verwaltung/abrechnung"),
                           ("verwaltung-kasse.jpg", "/verwaltung/kasse"),
                           ("verwaltung-buecher.jpg", "/verwaltung/buecher"), ("verwaltung-lager.jpg", "/verwaltung/lager"),
                           ("verwaltung-sortiment.jpg", "/verwaltung/sortiment"), ("verwaltung-einkauf.jpg", "/verwaltung/einkauf"),
                           ("verwaltung-geraete.jpg", "/verwaltung/geraete"), ("verwaltung-benutzer.jpg", "/verwaltung/benutzer"),
                           ("verwaltung-protokoll.jpg", "/verwaltung/protokoll"), ("verwaltung-einstellungen.jpg", "/verwaltung/einstellungen")]:
            shot(name, path)
        # Die Monate stehen unter dem Rand — mit allen zwölf sieht man, wo die Zahlen anfangen.
        shot("verwaltung-berichte.jpg", "/verwaltung/berichte", full=True, max_height=1160)
        c.go(BASE + "/verwaltung/einstellungen")
        snap("verwaltung-online.jpg",
             selector_js="[...document.querySelectorAll('section.panel')].find(p => (p.querySelector('h2')||{}).textContent === 'Online aufladen')")
        # Die Testanmeldung: Lena sieht, was der Kassier sieht — mit dem Hinweis oben.
        login(f"lena#karl@{KUERZEL}")
        snap("verwaltung-testanmeldung.jpg", selector_js="document.querySelector('main')", max_height=330)
        # Am Handy
        login("karl", None)
        c.size(390, 844, scale=2, mobile=True)
        shot("verwaltung-handy.jpg", "/verwaltung")
        # Systemverwaltung
        c.size(1280, 820)
        login("hanna@system", "/verwaltung/system")
        snap("system-verwaltung.jpg", full=True)
        # Der Deckel für Mitglieder, am Handy
        c.size(390, 844, scale=2, mobile=True)
        c.page("Network.clearBrowserCookies")
        shot("konto-anmelden.jpg", f"/konto/{KUERZEL}")
        token = new_id().replace("-", "")
        sql("INSERT INTO portal_links (token_hash, email, expires_at) VALUES (encode(sha256(convert_to('%s', 'UTF8')), 'hex'), 'maria.bauer@example.at', now() + interval '30 minutes');" % token)
        c.go(BASE + f"/konto/{KUERZEL}/link/{token}")
        c.submit({})
        shot("konto-deckel.jpg", f"/konto/{KUERZEL}", full=True)
        shot("konto-bezahlen.jpg", f"/konto/{KUERZEL}/bezahlen/{ids['pending']}")
        shot("konto-danke.jpg", f"/konto/{KUERZEL}/zahlung/{ids['paid']}")
    finally:
        c.close()


# ------------------------------------------------------------------ PDF

def pdf():
    from chrome import Chrome
    missing = [n for n in referenced_images() if not os.path.exists(os.path.join(BILDER, n + ".jpg"))]
    if missing:
        raise SystemExit(f"Es fehlen Bilder: {', '.join(missing)} — erst mit `alles {' '.join(missing)}` machen.")
    version = open(os.path.join(ROOT, "VERSION")).read().strip()
    label = version.replace("-beta", " Beta")
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    stand = datetime.now(ZONE).strftime("%d.%m.%Y")
    c = Chrome()
    try:
        c.go("file://" + os.path.join(DOCS, "anleitung.html"), settle=1.0)
        c.js("document.querySelectorAll('[data-version]').forEach(e => e.textContent = %s);"
             "document.querySelectorAll('[data-stand]').forEach(e => e.textContent = %s);"
             "document.querySelectorAll('[data-commit]').forEach(e => e.textContent = %s);" % (json.dumps(label), json.dumps(stand), json.dumps(commit)))
        footer = ('<div style="width:100%; font: 8px -apple-system, Helvetica, sans-serif; color:#5b625c; padding: 0 16mm; display:flex; justify-content:space-between;">'
                  f'<span>VereinsDeckel · Anleitung · Version {label}</span><span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>')
        c.pdf(PDF, footer)
    finally:
        c.close()
    log(f"PDF: {PDF} ({os.path.getsize(PDF) // 1024} KB)")


def main():
    step = sys.argv[1] if len(sys.argv) > 1 else "alles"
    names = sys.argv[2:]
    if names:
        if step not in ("alles", "web", "app"):
            raise SystemExit(f"Bildnamen gibt es nur hinter alles, web und app, nicht hinter {step}.")
        unknown = [n for n in names if n not in referenced_images()]
        if unknown:
            raise SystemExit(f"Diese Bilder zeigt die Anleitung nicht: {', '.join(unknown)}\n"
                             f"Sie zeigt: {' '.join(referenced_images())}")
        NUR.update(names)
    if step == "start":
        start()
    elif step == "stop":
        stop()
    elif step == "web":
        web()
    elif step == "app":
        import app
        app.NUR.update(NUR)
        app.run(BASE, TOKEN, BILDER, STATE)
    elif step == "pdf":
        pdf()
    elif step == "alles":
        wants_web = not NUR or any(not n.startswith("app-") for n in NUR)
        wants_app = not NUR or any(n.startswith("app-") for n in NUR)
        start()
        try:
            if wants_web:
                web()
            if wants_app:
                import app
                app.NUR.update(NUR)
                if app.emulator_ready():
                    app.run(BASE, TOKEN, BILDER, STATE)
                elif NUR:
                    raise SystemExit("Kein Emulator: Die verlangten Bilder der App lassen sich so nicht machen.")
                else:
                    log("Kein Emulator: die Bilder der App bleiben, wie sie sind")
        finally:
            stop()
        pdf()
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
