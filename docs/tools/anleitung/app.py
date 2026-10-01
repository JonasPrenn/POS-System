"""Bilder der App aus dem Android-Emulator.

Gebaut wird die App mit `-PappIdSuffix=.anleitung` und als eigenes Paket installiert — neben der
Entwicklungs-App, deren Daten unberührt bleiben. Gekoppelt wird sie an den Wegwerf-Server der
Anleitung (10.0.2.2), dann führt dieses Skript durch die Bildschirme und fotografiert sie.

Bedient wird über die Texte auf dem Bildschirm (uiautomator), nicht über Koordinaten: Ändert sich
das Layout, findet es die Knöpfe trotzdem; ändert sich ein Text, steht hier, welcher.

Am besten im Tablet-Format des Vereinsgeräts: `adb shell wm size 1920x1200` bei Dichte 320
(960 × 600 dp, Galaxy Tab Active3).
"""
import html
import json
import os
import re
import shutil
import subprocess
import time
import urllib.request

PACKAGE = "com.example.vereins_kassensystem.anleitung"


def adb_path():
    return shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def emulator_ready():
    adb = adb_path()
    if not os.path.exists(adb):
        return False
    out = subprocess.run([adb, "devices"], capture_output=True, text=True).stdout
    return any(line.startswith("emulator-") and line.endswith("\tdevice") for line in out.splitlines())


class Node:
    def __init__(self, text, desc, bounds, clickable):
        self.text, self.desc, self.clickable = text, desc, clickable
        self.x1, self.y1, self.x2, self.y2 = bounds
        self.cx, self.cy = (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    def __repr__(self):
        return f"{self.text or self.desc!r}@{self.cx},{self.cy}"


# Nur diese Bilder schreiben (Namen ohne .jpg), leer heißt alle — gesetzt von anleitung.py.
NUR = set()


class Device:
    def __init__(self, serial=None):
        self.adb = adb_path()
        if serial is None:
            out = subprocess.run([self.adb, "devices"], capture_output=True, text=True).stdout
            serial = next(line.split("\t")[0] for line in out.splitlines() if line.startswith("emulator-") and line.endswith("\tdevice"))
        self.serial = serial
        self._bars = None

    def run(self, *args, check=True, capture=True):
        return subprocess.run([self.adb, "-s", self.serial, *args], capture_output=capture, text=True, check=check).stdout

    def shell(self, *args):
        return self.run("shell", *args)

    def nodes(self):
        xml = subprocess.run([self.adb, "-s", self.serial, "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout
        found = []
        for m in re.finditer(r"<node [^>]*>", xml):
            n = m.group(0)
            text = html.unescape((re.search(r' text="([^"]*)"', n) or [None, ""])[1])
            desc = html.unescape((re.search(r' content-desc="([^"]*)"', n) or [None, ""])[1])
            b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n).group(1))))
            found.append(Node(text, desc, b, ' clickable="true"' in n))
        return found

    def find(self, label, contains=False):
        for n in self.nodes():
            for value in (n.text, n.desc):
                if value and (value == label or (contains and label in value)):
                    return n
        return None

    # Dialoge des Systems, die sich vordrängeln (etwa die Tastatur, die nach Kontakten fragt) — abgelehnt.
    POPUPS = ["Don’t allow", "Don't allow", "Nicht zulassen", "Ablehnen"]

    def wait(self, label, timeout=20, contains=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            nodes = self.nodes()
            for n in nodes:
                for value in (n.text, n.desc):
                    if value and (value == label or (contains and label in value)):
                        return n
            popup = next((n for n in nodes if n.text in self.POPUPS), None)
            if popup:
                self.shell("input", "tap", str(popup.cx), str(popup.cy))
            time.sleep(0.5)
        raise RuntimeError(f"Nicht auf dem Bildschirm: {label!r}. Sichtbar: {[x.text or x.desc for x in self.nodes() if x.text or x.desc][:40]}")

    def tap(self, label, timeout=20, contains=False, long=False):
        n = self.wait(label, timeout, contains)
        if long:
            self.shell("input", "swipe", str(n.cx), str(n.cy), str(n.cx), str(n.cy), "900")
        else:
            self.shell("input", "tap", str(n.cx), str(n.cy))
        time.sleep(0.6)
        return n

    def tap_xy(self, x, y):
        self.shell("input", "tap", str(x), str(y))
        time.sleep(0.5)

    def type(self, text):
        self.shell("input", "text", text.replace(" ", "%s"))
        time.sleep(0.3)

    def keyboard_shown(self):
        return "mInputShown=true" in self.shell("dumpsys", "input_method")

    def hide_keyboard(self):
        """Die Bildschirmtastatur liegt über der unteren Hälfte — ein Tipp dorthin träfe sie, nicht die App."""
        for code in ("111", "4"):
            if not self.keyboard_shown():
                return
            self.shell("input", "keyevent", code)
            time.sleep(0.6)

    def fill(self, label, value):
        """Ins Feld mit dieser Beschriftung: Tastatur weg, Feld antippen, leeren (Strg+A, Löschen), tippen."""
        self.hide_keyboard()
        self.tap(label)
        self.wait(label)  # eine Rückfrage des Systems ist inzwischen weggeklickt
        self.shell("input", "keycombination", "113", "29")
        self.shell("input", "keyevent", "67")
        self.type(value)
        self.hide_keyboard()

    def key(self, code):
        self.shell("input", "keyevent", str(code))
        time.sleep(0.4)

    def scroll_up(self, times=1):
        size = re.search(r"(\d+)x(\d+)\s*$", self.shell("wm", "size").strip()).groups()
        w, h = int(size[0]), int(size[1])
        for _ in range(times):
            self.shell("input", "swipe", str(w // 2), str(int(h * 0.3)), str(w // 2), str(int(h * 0.8)), "350")
            time.sleep(0.4)

    def scroll_down(self, times=1):
        size = re.search(r"(\d+)x(\d+)\s*$", self.shell("wm", "size").strip()).groups()
        w, h = int(size[0]), int(size[1])
        for _ in range(times):
            self.shell("input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.3)), "350")
            time.sleep(0.5)

    def bars(self):
        """Status- und Navigationsleiste als Anteil der Bildschirmhöhe (oben, unten). Sie kommen nicht
        ins Bild: Die Uhr dort ginge nach der Spracheinstellung des Emulators, nicht nach der App."""
        if self._bars is None:
            height = int(re.findall(r"size: \d+x(\d+)", self.shell("wm", "size"))[-1])  # Override steht nach Physical
            top = bottom = 0
            for y0, y1 in set(re.findall(r"type=(?:statusBars|navigationBars) frame=\[\d+,(\d+)\]\[\d+,(\d+)\]",
                                         self.shell("dumpsys", "window"))):
                y0, y1 = int(y0), int(y1)
                if y0 == 0 and y1 < height / 4:
                    top = max(top, y1)
                elif y1 == height and y0 > height * 3 / 4:
                    bottom = max(bottom, height - y0)
            self._bars = (top / height, bottom / height)
        return self._bars

    def shot(self, path, settle=1.0):
        time.sleep(settle)
        if NUR and os.path.basename(path)[:-4] not in NUR:
            return
        png = path[:-4] + ".png"
        with open(png, "wb") as f:
            subprocess.run([self.adb, "-s", self.serial, "exec-out", "screencap", "-p"], stdout=f, check=True)
        info = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", png], capture_output=True, text=True, check=True).stdout
        width = int(re.search(r"pixelWidth: (\d+)", info).group(1))
        height = int(re.search(r"pixelHeight: (\d+)", info).group(1))
        top, bottom = (round(f * height) for f in self.bars())
        subprocess.run(["sips", "--cropToHeightWidth", str(height - top - bottom), str(width), "--cropOffset", str(top), "0", png],
                       capture_output=True, check=True)
        # JPEG, 1440 breit: scharf genug fürs PDF, klein genug fürs Repo.
        subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "78", "--resampleWidth", "1440", png, "--out", path],
                       capture_output=True, check=True)
        os.remove(png)
        print(f"· Bild {os.path.basename(path)}", flush=True)

    def start_app(self):
        self.shell("am", "force-stop", PACKAGE)
        self.shell("monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(3)


def api(base, path, body, token):
    request = urllib.request.Request(base + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=30) as r:
        return json.loads(r.read().decode())


# ------------------------------------------------------------------ Ablauf

def go(d, label):
    """In der Leiste oder unter „Mehr“: was auf einem kleinen Tablet nicht in die Leiste passt."""
    if d.find(label) is None or label not in ("Verkauf", "Übersicht", "Historie"):
        if d.find("Mehr"):
            d.tap("Mehr")
    d.tap(label)
    time.sleep(1.0)


def pair(d, base, token):
    """Mehr → Einstellungen → Server und Abgleich: Adresse, Code, Name, Koppeln."""
    code = api(base, "/v1/admin/pairing-codes", {}, token)["code"]
    port = base.rsplit(":", 1)[1]
    go(d, "Einstellungen")
    for _ in range(8):
        if d.find("Adresse des Servers"):
            break
        d.scroll_down()
    d.fill("Adresse des Servers", f"http://10.0.2.2:{port}")
    d.fill("Kopplungscode", code)
    d.fill("Name dieses Geräts", "Theke links")
    d.key(111)  # Tastatur weg
    d.tap("Koppeln")
    d.wait("Jetzt abgleichen", timeout=40)
    time.sleep(4)


def run(base, token, bilder, state):
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    apk = os.path.join(root, "androidApp", "build", "outputs", "apk", "debug", "androidApp-debug.apk")
    env = dict(os.environ)
    studio_jdk = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if os.path.isdir(studio_jdk):
        env["JAVA_HOME"] = studio_jdk
    subprocess.run([os.path.join(root, "gradlew"), "-q", ":androidApp:assembleDebug", "-PappIdSuffix=.anleitung"], cwd=root, env=env, check=True)
    d = Device()
    d.run("install", "-r", apk)
    d.shell("pm", "clear", PACKAGE)
    d.start_app()
    d.wait("Verkauf", timeout=40)
    pair(d, base, token)
    steps(d, bilder, base)
    d.shell("am", "force-stop", PACKAGE)
    d.run("uninstall", PACKAGE, check=False)


def count(d, pieces):
    """In einem Zähldialog: Schein oder Münze antippen, Anzahl über das Tastenfeld."""
    for label, digits in pieces:
        d.tap(label)
        for ch in digits:
            d.tap(ch)


def min_version(base, version):
    """Die Mindestversion in der Systemverwaltung des Wegwerf-Servers setzen (für das Bild der Sperre)."""
    import http.cookiejar
    import urllib.parse
    from anleitung import PASSWORT
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    opener.open(base + "/verwaltung/anmelden", urllib.parse.urlencode({"login": "hanna@system", "passwort": PASSWORT}).encode()).read()
    page = opener.open(base + "/verwaltung/system").read().decode()
    csrf = re.search(r'name="_csrf" value="([^"]+)"', page).group(1)
    opener.open(base + "/verwaltung/system", urllib.parse.urlencode({"_csrf": csrf, "teil": "mindestversion", "version": version}).encode()).read()


def steps(d, bilder, base):
    """Die Bildschirme der Anleitung, in der Reihenfolge einer Schicht."""
    p = lambda name: os.path.join(bilder, name)

    go(d, "Verkauf")
    d.wait("Kasse öffnen")
    d.shot(p("app-verkauf-kasse-zu.jpg"))

    d.tap("Kasse öffnen")
    d.tap("Wer öffnet: Mitglied wählen")
    d.tap("Lukas Hofer v. Sokrates")
    count(d, [("50 €", "1"), ("20 €", "2"), ("10 €", "3"), ("5 €", "2"), ("2 €", "5"), ("1 €", "10")])  # 150 €
    d.shot(p("app-kasse-oeffnen.jpg"))
    d.tap("Öffnen")

    for _ in range(2):
        d.tap("Helles, Varianten verfügbar")
        d.tap("0,5 l")
    d.tap("Bratwurst mit Senf, 4,50 €")
    d.tap("Mitglied auswählen")
    d.wait("Mitglied auswählen")
    d.shot(p("app-mitglied-waehlen.jpg"))
    d.tap("Lukas Hofer v. Sokrates")
    d.shot(p("app-verkauf-warenkorb.jpg"))

    d.tap("Bezahlen")
    d.wait("Zahlung wählen")
    d.shot(p("app-bezahlen.jpg"))
    d.tap("Bar")
    d.tap("20")
    d.shot(p("app-bar.jpg"))
    d.tap("Abschließen")

    d.tap("Chips, 2,00 €", long=True)
    d.wait("Ausblenden")
    d.shot(p("app-ausblenden.jpg"))
    d.key(4)  # Menü zu, nichts ausgeblendet

    go(d, "Übersicht")
    d.wait("Kasse schließen")
    d.shot(p("app-uebersicht.jpg"))
    d.tap("Kasse schließen")
    count(d, [("50 €", "1"), ("20 €", "3"), ("10 €", "3"), ("5 €", "2"), ("2 €", "6"), ("20 ct", "2")])  # 162,40 € — 50 ct zu wenig
    d.shot(p("app-kasse-schliessen.jpg"))
    d.tap("Abbrechen")

    go(d, "Historie")
    d.shot(p("app-historie.jpg"))
    d.tap("Mehr")
    d.wait("Lagerbestand")
    d.shot(p("app-mehr.jpg"))
    d.tap("Mitglieder")
    d.wait("Mitglied suchen")
    d.shot(p("app-mitglieder.jpg"))
    d.tap("Guthaben aufladen für Lukas Hofer")
    d.tap("20 €")
    d.shot(p("app-aufladen.jpg"))
    d.tap("Abbrechen")

    for label, name in [("Produkte", "app-produkte.jpg"), ("Lagerbestand", "app-lager.jpg"), ("Kategorien", "app-kategorien.jpg")]:
        go(d, label)
        d.shot(p(name), settle=1.5)
    go(d, "Auswertung")
    d.tap("7 Tage")
    d.shot(p("app-auswertung.jpg"), settle=1.5)

    go(d, "Einstellungen")
    d.scroll_up(6)
    d.shot(p("app-einstellungen.jpg"))
    for _ in range(8):
        if d.find("Jetzt abgleichen"):
            break
        d.scroll_down()
    d.shot(p("app-server.jpg"))

    # Zuletzt die Sperre: Mindestversion über der App, abgleichen, fotografieren, zurücksetzen.
    min_version(base, "9.0.0")
    d.tap("Jetzt abgleichen")
    d.wait("Diese App muss aktualisiert werden", timeout=90)
    d.shot(p("app-sperre.jpg"))
    min_version(base, "0.0.0")
