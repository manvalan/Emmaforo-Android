#!/usr/bin/env python3
"""Pubblica su emmaforo.michelebigi.it l'app Android, le pagine del sito e il firmware.

    python tools/pubblica.py app                       # build release firmata + pagine + APK
    python tools/pubblica.py app --apk release/emmaforo-1.2.apk   # APK già firmato (per esempio dal Mac)
    python tools/pubblica.py sito                      # solo le pagine (site/)
    python tools/pubblica.py firmware emmaforo.bin 1.2 # firmware e nota di versione
    python tools/pubblica.py ... --prova               # mostra cosa caricherebbe, senza caricare

Il server si descrive in pubblica.ini (non va in git), accanto a questo repo:

    [server]
    protocollo = ftps        ; ftp, ftps oppure sftp
    host = ftp.example.it
    utente = nome
    cartella = /htdocs       ; la radice del sito sul server

La password non sta nel file: la prende da EMMAFORO_SITO_PASSWORD oppure la chiede.
Con sftp si usa il client OpenSSH di sistema, con la chiave SSH già configurata.
"""

import argparse
import configparser
import ftplib
import getpass
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
APK_OUT = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"


def load_server():
    path = ROOT / "pubblica.ini"
    if not path.exists():
        sys.exit(f"Manca {path}: copia l'esempio in cima a tools/pubblica.py e compilalo.")
    config = configparser.ConfigParser(inline_comment_prefixes=(";", "#"))
    config.read(path, encoding="utf-8")
    server = config["server"]
    return {
        "protocollo": server.get("protocollo", "ftps").strip().lower(),
        "host": server["host"].strip(),
        "utente": server["utente"].strip(),
        "cartella": server.get("cartella", "/").strip().rstrip("/") or "",
        "porta": server.getint("porta", fallback=0),
    }


def build_release():
    java = ROOT / ".jdk"
    env = dict(os.environ)
    homes = sorted(java.glob("jdk-*")) if java.exists() else []
    if homes:
        env["JAVA_HOME"] = str(homes[-1])
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(gradle), "assembleRelease", "-q"], cwd=ROOT, env=env, check=True)
    if not APK_OUT.exists():
        sys.exit("La build non ha prodotto l'APK release.")
    if not (ROOT / "keystore.properties").exists():
        sys.exit("Manca keystore.properties: l'APK non sarebbe firmato con la chiave release.")
    target = SITE / "app" / "emmaforo.apk"
    shutil.copyfile(APK_OUT, target)
    return target


def site_files(with_apk):
    files = [
        (SITE / "index.html", "index.html"),
        (SITE / "app" / "index.html", "app/index.html"),
        (SITE / "app" / "emmaforo-app-qr.svg", "app/emmaforo-app-qr.svg"),
        (SITE / "app" / "emmaforo-app-qr.png", "app/emmaforo-app-qr.png"),
    ]
    if with_apk:
        # The APK goes up before the pages that link to it.
        files.insert(0, (SITE / "app" / "emmaforo.apk", "app/emmaforo.apk"))
    return files


def firmware_files(binary, version, scratch):
    note = Path(scratch) / "version.txt"
    note.write_text(version.strip() + "\n", encoding="utf-8")
    # The image first: the app offers the update as soon as version.txt changes.
    return [(Path(binary), "firmware/emmaforo.bin"), (note, "firmware/version.txt")]


def upload_ftp(server, files, password):
    secure = server["protocollo"] == "ftps"
    ftp = ftplib.FTP_TLS() if secure else ftplib.FTP()
    ftp.connect(server["host"], server["porta"] or 21, timeout=60)
    ftp.login(server["utente"], password)
    if secure:
        ftp.prot_p()
    try:
        for local, remote in files:
            path = f"{server['cartella']}/{remote}"
            folder = path.rsplit("/", 1)[0]
            ensure_ftp_dir(ftp, folder)
            temporary = path + ".part"
            with open(local, "rb") as handle:
                ftp.storbinary(f"STOR {temporary}", handle)
            try:
                ftp.delete(path)
            except ftplib.error_perm:
                pass
            ftp.rename(temporary, path)
            print(f"caricato {remote}")
    finally:
        ftp.quit()


def ensure_ftp_dir(ftp, folder):
    current = ""
    for part in [p for p in folder.split("/") if p]:
        current += "/" + part
        try:
            ftp.mkd(current)
        except ftplib.error_perm:
            pass


def upload_sftp(server, files):
    lines = []
    made = set()
    for local, remote in files:
        path = f"{server['cartella']}/{remote}"
        folder = path.rsplit("/", 1)[0]
        if folder and folder not in made:
            lines.append(f"-mkdir {folder}")
            made.add(folder)
        lines.append(f'put "{local}" "{path}.part"')
        lines.append(f'-rm "{path}"')
        lines.append(f'rename "{path}.part" "{path}"')
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as batch:
        batch.write("\n".join(lines) + "\n")
    command = ["sftp", "-b", batch.name]
    if server["porta"]:
        command += ["-P", str(server["porta"])]
    command.append(f"{server['utente']}@{server['host']}")
    try:
        subprocess.run(command, check=True)
    finally:
        os.unlink(batch.name)
    for _, remote in files:
        print(f"caricato {remote}")


def main():
    parser = argparse.ArgumentParser(description="Pubblica app, sito e firmware di Emmaforo.")
    parser.add_argument("cosa", choices=["app", "sito", "firmware"])
    parser.add_argument("binario", nargs="?", help="per firmware: il file .bin")
    parser.add_argument("versione", nargs="?", help="per firmware: la versione, per esempio 1.2")
    parser.add_argument("--apk", help="per app: carica questo APK già firmato invece di compilare")
    parser.add_argument("--prova", action="store_true", help="mostra i file senza caricarli")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as scratch:
        if args.cosa == "app":
            if args.apk:
                shutil.copyfile(args.apk, SITE / "app" / "emmaforo.apk")
            elif not args.prova:
                build_release()
            files = site_files(with_apk=True)
        elif args.cosa == "sito":
            files = site_files(with_apk=False)
        else:
            if not args.binario or not args.versione:
                sys.exit("Uso: pubblica.py firmware <file.bin> <versione>")
            files = firmware_files(args.binario, args.versione, scratch)

        missing = [str(local) for local, _ in files if not Path(local).exists() and not args.prova]
        if missing:
            sys.exit("Mancano: " + ", ".join(missing))

        server = load_server() if not args.prova or (ROOT / "pubblica.ini").exists() else None
        where = f"{server['protocollo']}://{server['host']}{server['cartella']}/" if server else "(server non configurato)"
        for local, remote in files:
            print(f"{local} -> {where}{remote}")
        if args.prova:
            return
        if server["protocollo"] == "sftp":
            upload_sftp(server, files)
        else:
            password = os.environ.get("EMMAFORO_SITO_PASSWORD") or getpass.getpass(f"Password per {server['utente']}@{server['host']}: ")
            upload_ftp(server, files, password)
    print("Fatto.")


if __name__ == "__main__":
    main()
