"""Optional Java/API compile check, not an APK build or Android runtime test.

Downloads official Microsoft OpenJDK and Maven Central's Robolectric API jar
to ignored runtime/. Does not install SDKs, accept SDK terms or alter PATH.
"""
import hashlib
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parent
TOOLS=ROOT/'runtime'/'compile-check'


def download(url,target,checksum_url,algorithm):
    if target.exists():return
    expected=urllib.request.urlopen(checksum_url,timeout=30).read().decode().strip().split()[0].lower()
    if not re.fullmatch('[0-9a-f]+',expected):raise ValueError('invalid published checksum')
    print('Downloading '+target.name,flush=True)
    with urllib.request.urlopen(url,timeout=60) as source,target.open('wb') as output:
        while chunk:=source.read(1024*1024):output.write(chunk)
    actual=hashlib.file_digest(target.open('rb'),algorithm).hexdigest()
    if actual!=expected:raise ValueError('checksum mismatch; do not execute downloaded file')
    target.with_suffix(target.suffix+'.verified').write_text(actual)


def main():
    TOOLS.mkdir(parents=True,exist_ok=True)
    jdk_url='https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip'
    archive=TOOLS/'jdk.zip'
    download(jdk_url,archive,jdk_url+'.sha256sum.txt','sha256')
    if not archive.with_suffix('.zip.verified').exists():raise ValueError('JDK is not verified')
    with zipfile.ZipFile(archive) as zip_file:
        for entry in zip_file.infolist():
            target=(TOOLS/entry.filename).resolve()
            if not target.is_relative_to(TOOLS.resolve()):raise ValueError('unsafe archive path')
        zip_file.extractall(TOOLS)
    javac=next(TOOLS.glob('jdk-*/bin/javac.exe'))
    artifact='https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar'
    android=TOOLS/'android-api.jar'
    download(artifact,android,artifact+'.sha512','sha512')
    if not android.with_suffix('.jar.verified').exists():raise ValueError('API jar is not verified')
    generated=TOOLS/'BuildConfig.java'
    generated.write_text('package org.dravet.guard; public final class BuildConfig { public static final boolean DEBUG=true; }',encoding='utf-8')
    sources=list((ROOT.parent/'android/app/src/main/java').rglob('*.java'))
    classes=TOOLS/'classes';classes.mkdir(exist_ok=True)
    subprocess.run([str(javac),'-encoding','UTF-8','--release','17','-cp',str(android),'-d',str(classes),str(generated),*map(str,sources)],check=True)
    print('Java/API compile check passed. This is not an APK build or device test.',flush=True)


if __name__=='__main__':main()
