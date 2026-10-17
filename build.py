#!/usr/bin/env python3
"""Build RewindReplay.jar and run the offline verifier with plain javac (no Gradle or Maven needed).

usage:
  python3 build.py [build] [--out DIR]       compile src/main and write DIR/RewindReplay.jar (default: ./build)
  python3 build.py test [-Dkey=value ...]    compile src/main + src/test and run the offline verifier
                                             (-D options are passed to the test JVM, see VerifyReplay.java)
options (both commands):
  --cp JARS       compile classpath, jar paths separated by the OS path separator (also: RR_CLASSPATH env variable)
  --server DIR    take the classpath from a Paper/Folia 26.2 server folder that has been started once: the libraries
                  listed in its server jar (META-INF/libraries.list, extracted under DIR/libraries) plus
                  DIR/plugins/packetevents-spigot-*.jar and DIR/plugins/CoreProtect-*.jar
  --jdk DIR       JDK home (default: $JAVA_HOME, else javac/java/jar from PATH); JDK 25 or newer
Without --cp / RR_CLASSPATH / --server, every *.jar in ./libs is used (see README, "Build").
Building does not install anything: copy the jar into your server's plugins/ folder yourself.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
JAR_NAME = 'RewindReplay.jar'
MAIN_TEST = 'io.github.jayden0903.replay.VerifyReplay'
# class -> what provides it (checked before javac so a missing jar gives a clear message)
REQUIRED = {
    'org/bukkit/Bukkit.class': 'Paper API or Folia API 26.2 (paper-api-26.2.*.jar / folia-api-26.2.*.jar)',
    'net/kyori/adventure/text/Component.class': 'adventure-api (a Paper API dependency)',
    'net/kyori/adventure/key/Key.class': 'adventure-key (a Paper API dependency)',
    'com/google/gson/Gson.class': 'gson (a Paper API dependency)',
    'com/google/common/collect/Multimap.class': 'guava (a Paper API dependency)',
    'net/md_5/bungee/api/chat/BaseComponent.class': 'bungeecord-chat (a Paper API dependency)',
    'com/github/retrooper/packetevents/PacketEvents.class': 'PacketEvents 2.14 for Spigot/Paper (packetevents-spigot-2.14.*.jar)',
    'net/coreprotect/CoreProtectAPI.class': 'CoreProtect (API 9+; needed to compile, optional on the server)',
}
# needed by some API builds only (javac fails on the public paper-api jar without it): a warning, not an error
RECOMMENDED = {'org/jetbrains/annotations/NotNull.class': 'org.jetbrains:annotations (the public paper-api jar needs it at compile time)'}


def tool(jdk, name):
    if jdk:
        return str(Path(jdk) / 'bin' / name)
    found = shutil.which(name)
    if not found:
        sys.exit(f'{name} not found: pass --jdk or set JAVA_HOME (JDK 25+)')
    return found


def server_classpath(d):
    d = Path(d).resolve()
    bundler = None
    for j in sorted(d.glob('*.jar')):
        try:
            with zipfile.ZipFile(j) as z:
                if 'META-INF/libraries.list' in z.namelist():
                    bundler = j
                    listed = [line.split('\t')[2] for line in z.read('META-INF/libraries.list').decode().splitlines() if line.strip()]
                    break
        except (zipfile.BadZipFile, IndexError):
            continue
    if bundler is None:
        sys.exit(f'--server {d}: no Paper/Folia server jar with META-INF/libraries.list found')
    libs = d / 'libraries'
    cp = [libs / x for x in listed if (libs / x).exists()]
    if not cp:
        sys.exit(f'--server {d}: {libs} is empty; start the server once so it extracts its libraries')
    cp += sorted((d / 'plugins').glob('packetevents-spigot-*.jar')) + sorted((d / 'plugins').glob('CoreProtect-*.jar'))
    return cp


def classpath(args):
    explicit = args.cp or os.environ.get('RR_CLASSPATH')
    if explicit:
        cp = [Path(x) for x in explicit.split(os.pathsep) if x]
    elif args.server:
        cp = server_classpath(args.server)
    else:
        cp = sorted((ROOT / 'libs').glob('*.jar'))
    missing_files = [str(p) for p in cp if not p.is_file()]
    if missing_files:
        sys.exit('classpath entries not found: ' + ', '.join(missing_files))
    present = set()
    for p in cp:
        try:
            with zipfile.ZipFile(p) as z:
                present.update(n for n in z.namelist() if n in REQUIRED or n in RECOMMENDED)
        except zipfile.BadZipFile:
            sys.exit(f'not a jar: {p}')
    missing = [what for cls, what in REQUIRED.items() if cls not in present]
    if missing:
        sys.exit('compile classpath is missing:\n  - ' + '\n  - '.join(missing)
                 + '\nPut the jars into libs/, or use --cp / RR_CLASSPATH / --server (see README, "Build").')
    for cls, what in RECOMMENDED.items():
        if cls not in present:
            print('note: classpath has no ' + what, file=sys.stderr)
    return cp


def javac(jdk, cp, sources, out):
    cmd = [tool(jdk, 'javac'), '--release', '25', '-proc:none', '-encoding', 'UTF-8',
           '-Xlint:all,-deprecation,-serial,-processing,-classfile,-path',
           '-cp', os.pathsep.join(map(str, cp)), '-d', str(out), *map(str, sources)]
    if subprocess.run(cmd).returncode != 0:
        sys.exit('javac failed')


def main():
    ap = argparse.ArgumentParser(description='Build RewindReplay or run its offline verifier.', add_help=True)
    ap.add_argument('command', nargs='?', default='build', choices=['build', 'test'])
    ap.add_argument('--out', default=str(ROOT / 'build'))
    ap.add_argument('--cp')
    ap.add_argument('--server')
    ap.add_argument('--jdk', default=os.environ.get('JAVA_HOME'))
    args, extra = ap.parse_known_args()
    bad = [x for x in extra if not x.startswith('-D')]
    if bad:
        ap.error('unknown arguments: ' + ' '.join(bad))
    cp = classpath(args)
    main_src = sorted((ROOT / 'src/main/java').rglob('*.java'))
    with tempfile.TemporaryDirectory(prefix='rr-classes-') as tmp:
        out = Path(tmp)
        if args.command == 'build':
            javac(args.jdk, cp, main_src, out)
            res = ROOT / 'src/main/resources'
            outdir = Path(args.out).resolve()
            outdir.mkdir(parents=True, exist_ok=True)
            jar = outdir / JAR_NAME
            if jar.exists():
                jar.unlink()
            subprocess.run([tool(args.jdk, 'jar'), 'cf', str(jar), '-C', str(out), '.',
                            '-C', str(res), 'plugin.yml', '-C', str(res), 'config.yml'], check=True)
            print('built', jar)
        else:
            javac(args.jdk, cp, main_src + sorted((ROOT / 'src/test/java').rglob('*.java')), out)
            run = [tool(args.jdk, 'java'), '-Xmx2G', *extra, '-cp', os.pathsep.join([str(out), *map(str, cp)]), MAIN_TEST]
            sys.exit(subprocess.run(run).returncode)


if __name__ == '__main__':
    main()
