"""
PDF para gráfica, isolado: recebe o PDF que a API gerou e devolve o PDF/X-1a em CMYK (Ghostscript).

Roda num contêiner sem rede para fora, sem acesso ao banco nem às imagens, com o disco só de leitura (menos /tmp),
sem privilégios e com memória limitada: mesmo uma falha nova do Ghostscript não alcança o resto do Booksmith.

POST /convert   corpo: o PDF; cabeçalho X-Title: o título em UTF-16 (hexadecimal, começando por FEFF)
GET  /health    200 com a versão do Ghostscript
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PRINT = "/app/print"
ICC = f"{PRINT}/ISOcoated_v2_eci.icc"
TEMPLATE = f"{PRINT}/pdfx-1a.ps"
MAX_BYTES = 300 * 1024 * 1024
TIMEOUT = int(os.environ.get("PDFX_TIMEOUT", "170"))
HEX_TITLE = re.compile(r"^FEFF(?:[0-9A-F]{4}){0,400}$")
# Até duas conversões ao mesmo tempo (o contêiner tem pouca CPU e memória); as outras esperam
SLOTS = threading.Semaphore(2)


def ghostscript_version():
    return subprocess.run(["gs", "--version"], capture_output=True, text=True, timeout=20).stdout.strip()


def convert(pdf, title_hex):
    work = tempfile.mkdtemp(prefix="pdfx-")
    try:
        with open(TEMPLATE, encoding="utf-8") as f:
            definition = f.read().replace("%ICC%", ICC).replace("%TITLE%", f"<{title_hex}>")
        with open(f"{work}/def.ps", "w", encoding="latin-1") as f:
            f.write(definition)
        with open(f"{work}/in.pdf", "wb") as f:
            f.write(pdf)
        result = subprocess.run(
            ["gs", "-dSAFER", f"--permit-file-read={PRINT}/", f"--permit-file-read={work}/",
             "-dBATCH", "-dNOPAUSE", "-dQUIET", "-sDEVICE=pdfwrite", "-dPDFX=1", "-dCompatibilityLevel=1.3",
             "-sColorConversionStrategy=CMYK", f"-sOutputICCProfile={ICC}", "-dPDFSETTINGS=/prepress",
             f"-sOutputFile={work}/out.pdf", f"{work}/def.ps", f"{work}/in.pdf"],
            capture_output=True, timeout=TIMEOUT, cwd=work)
        log = (result.stdout + result.stderr).decode("utf-8", "replace")
        if result.returncode != 0 or not os.path.exists(f"{work}/out.pdf"):
            return 500, "gs_failed", log[-2000:]
        # O Ghostscript avisa e grava um PDF comum quando algo impede o padrão: não serve para a gráfica
        if "reverting to normal PDF" in log:
            return 422, "not_pdfx", log[-2000:]
        with open(f"{work}/out.pdf", "rb") as f:
            return 200, f.read(), None
    except subprocess.TimeoutExpired:
        return 504, "timeout", None
    finally:
        shutil.rmtree(work, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "booksmith-pdfx"

    def do_GET(self):
        if self.path != "/health":
            return self.reply(404, "not_found")
        body = json.dumps({"status": "UP", "ghostscript": ghostscript_version()}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/convert":
            return self.reply(404, "not_found")
        length = int(self.headers.get("Content-Length") or 0)
        title = (self.headers.get("X-Title") or "").upper()
        if length <= 0 or length > MAX_BYTES:
            return self.reply(413, "too_large")
        if not HEX_TITLE.match(title):
            return self.reply(400, "bad_title")
        pdf = self.rfile.read(length)
        if not pdf.startswith(b"%PDF-"):
            return self.reply(400, "not_pdf")
        with SLOTS:
            status, payload, log = convert(pdf, title)
        if status != 200:
            self.log_message("conversão falhou (%s): %s", payload, log or "")
            return self.reply(status, payload)
        self.send_response(200)
        self.send_header("Content-Type", "application/pdf")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def reply(self, status, code):
        body = json.dumps({"error": code}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8085"))
    print(f"pdfx no ar na porta {port} (Ghostscript {ghostscript_version()})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
