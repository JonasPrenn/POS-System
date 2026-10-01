"""Chrome ohne Fenster, ferngesteuert über das DevTools-Protokoll — für Screenshots der
Verwaltung und das PDF der Anleitung. Nur Standardbibliothek: ein kleiner WebSocket-Client
(RFC 6455) und die paar Befehle, die gebraucht werden."""
import base64
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import time

CHROME_CANDIDATES = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "google-chrome", "chromium", "chromium-browser",
]


def find_chrome():
    for c in CHROME_CANDIDATES:
        if os.path.isabs(c) and os.path.exists(c):
            return c
        if not os.path.isabs(c) and shutil.which(c):
            return shutil.which(c)
    raise SystemExit("Kein Chrome gefunden — die Anleitung braucht Google Chrome oder Chromium.")


class WebSocket:
    """Gerade genug WebSocket für das DevTools-Protokoll: Text hin, Text zurück."""

    def __init__(self, url):
        m = re.match(r"ws://([^:/]+):(\d+)(/.*)", url)
        host, port, path = m.group(1), int(m.group(2)), m.group(3)
        self.sock = socket.create_connection((host, port), timeout=120)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        ).encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(1)
            if not chunk:
                raise ConnectionError("DevTools: Verbindung beim Aufbau geschlossen")
            head += chunk
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise ConnectionError("DevTools: kein WebSocket: " + head.decode(errors="replace"))

    def send(self, text):
        payload = text.encode()
        header = bytearray([0x81])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        self.sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def _exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(min(1 << 20, n - len(buf)))
            if not chunk:
                raise ConnectionError("DevTools: Verbindung geschlossen")
            buf += chunk
        return bytes(buf)

    def recv(self):
        message = bytearray()
        while True:
            b1, b2 = self._exact(2)
            opcode = b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._exact(8))[0]
            mask = self._exact(4) if b2 & 0x80 else None
            data = self._exact(n)
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x9:  # Ping
                continue
            if opcode == 0x8:
                raise ConnectionError("DevTools: Verbindung geschlossen")
            message += data
            if b1 & 0x80:
                return message.decode()


class Chrome:
    """Ein Chrome ohne Fenster mit einem Tab. `page(...)`-Befehle gehen an den Tab."""

    def __init__(self):
        self.profile = tempfile.mkdtemp(prefix="vd-anleitung-chrome-")
        self.proc = subprocess.Popen(
            [find_chrome(), "--headless=new", "--remote-debugging-port=0", f"--user-data-dir={self.profile}",
             "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--hide-scrollbars",
             "--force-color-profile=srgb", "--lang=de-AT", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
        )
        url = None
        deadline = time.time() + 30
        while time.time() < deadline and url is None:
            line = self.proc.stderr.readline()
            m = re.search(r"DevTools listening on (ws://\S+)", line)
            if m:
                url = m.group(1)
        if url is None:
            self.close()
            raise SystemExit("Chrome hat keinen DevTools-Zugang geöffnet.")
        self.ws = WebSocket(url)
        self.next_id = 0
        self.events = []
        target = self.call("Target.createTarget", {"url": "about:blank"})["targetId"]
        self.session = self.call("Target.attachToTarget", {"targetId": target, "flatten": True})["sessionId"]
        for domain in ("Page", "Runtime", "Network"):
            self.page(f"{domain}.enable")
        # Gedruckt wird hell, egal wie der Rechner eingestellt ist.
        self.page("Emulation.setEmulatedMedia", {"features": [{"name": "prefers-color-scheme", "value": "light"}]})

    def call(self, method, params=None, session=None, timeout=120):
        self.next_id += 1
        msg = {"id": self.next_id, "method": method, "params": params or {}}
        if session:
            msg["sessionId"] = session
        self.ws.send(json.dumps(msg))
        deadline = time.time() + timeout
        while time.time() < deadline:
            answer = json.loads(self.ws.recv())
            if answer.get("id") == self.next_id:
                if "error" in answer:
                    raise RuntimeError(f"{method}: {answer['error']}")
                return answer.get("result", {})
            self.events.append(answer)
        raise TimeoutError(method)

    def page(self, method, params=None, timeout=120):
        return self.call(method, params, self.session, timeout)

    def wait_event(self, name, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for i, e in enumerate(self.events):
                if e.get("method") == name:
                    del self.events[i]
                    return e
            answer = json.loads(self.ws.recv())
            if "id" not in answer:
                self.events.append(answer)
        raise TimeoutError(name)

    def size(self, width, height, scale=1.5, mobile=False):
        self.page("Emulation.setDeviceMetricsOverride", {
            "width": width, "height": height, "deviceScaleFactor": scale, "mobile": mobile,
        })
        self.page("Emulation.setTouchEmulationEnabled", {"enabled": mobile})

    def go(self, url, settle=0.4):
        self.events.clear()
        self.page("Page.navigate", {"url": url})
        self.wait_event("Page.loadEventFired")
        time.sleep(settle)

    def js(self, expression):
        """Für Formulare: Felder füllen und abschicken — die Seiten selbst haben keine Skripte."""
        return self.page("Runtime.evaluate", {"expression": expression, "returnByValue": True, "awaitPromise": True}).get("result", {}).get("value")

    def submit(self, fields, form_selector="form"):
        """Füllt Felder (name → Wert) im Formular und schickt es ab; wartet auf die nächste Seite."""
        script = "(() => { const f = document.querySelector(%s); if (!f) return 'kein Formular';" % json.dumps(form_selector)
        for name, value in fields.items():
            script += "{ const e = f.querySelector('[name=%s]'); if (!e) return 'kein Feld %s'; e.value = %s; }" % (name, name, json.dumps(value))
        script += "f.submit(); return true; })()"
        self.events.clear()
        result = self.js(script)
        if result is not True:
            raise RuntimeError(f"Formular nicht abgeschickt: {result} auf {self.js('location.href')}")
        self.wait_event("Page.loadEventFired")
        time.sleep(0.4)

    def shot(self, path, selector=None, selector_js=None, full=False, max_height=None, pad=0, quality=78):
        """Ein JPEG: der sichtbare Teil, die ganze Seite ([full]), oder nur ein Element — per CSS-Selektor
        oder per JavaScript-Ausdruck, der das Element liefert, mit [pad] Pixeln Rand. [max_height] schneidet unten ab."""
        params = {"format": "jpeg", "quality": quality}
        element = selector_js or (f"document.querySelector({json.dumps(selector)})" if selector else None)
        if element:
            x, y, w, h = self.js("(() => { const e = %s; const r = e.getBoundingClientRect();"
                                 " return [r.left + scrollX, r.top + scrollY, r.width, r.height]; })()" % element)
            x, y, w, h = max(0, x - pad), max(0, y - pad), w + 2 * pad, h + 2 * pad
        elif full:
            x, y = 0, 0
            w = self.js("document.documentElement.clientWidth")
            h = min(self.js("document.documentElement.scrollHeight"), 4000)
        else:
            x, y = 0, 0
            w = self.js("document.documentElement.clientWidth")
            h = self.js("window.innerHeight")
        if max_height:
            h = min(h, max_height)
        params.update({"clip": {"x": x, "y": y, "width": w, "height": h, "scale": 1}, "captureBeyondViewport": True})
        data = self.page("Page.captureScreenshot", params)["data"]
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))

    def pdf(self, path, footer):
        data = self.page("Page.printToPDF", {
            "printBackground": True, "preferCSSPageSize": True, "displayHeaderFooter": True,
            "headerTemplate": "<div></div>",
            "footerTemplate": footer,
            "generateDocumentOutline": True, "generateTaggedPDF": True,
        }, timeout=300)["data"]
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))

    def close(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.profile, ignore_errors=True)
