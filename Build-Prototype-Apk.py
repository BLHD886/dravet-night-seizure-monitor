"""Build a debug APK using official SDK tools, without downloading Gradle plugins.

This project has no Android resources or external Android libraries, so aapt2,
javac, D8, zipalign and apksigner suffice. Requires an existing JDK17. Tools are
downloaded to ignored prototype/runtime, never installed system-wide.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parent
RUNTIME=ROOT/'prototype/runtime'
BUILD=RUNTIME/'apk-build'
ANDROID_NS='http://schemas.android.com/apk/res/android'


def fetch(name,url,expected,size):
    archive=BUILD/name
    if archive.exists() and archive.stat().st_size==size:
        with archive.open('rb') as stream:
            if hashlib.file_digest(stream,'sha1').hexdigest()==expected:return archive
    print('Downloading official SDK archive: '+name,flush=True)
    temporary=archive.with_suffix('.download')
    with urllib.request.urlopen(url,timeout=60) as response,temporary.open('wb') as output:
        while chunk:=response.read(1024*1024):output.write(chunk)
    with temporary.open('rb') as stream:actual=hashlib.file_digest(stream,'sha1').hexdigest()
    if actual!=expected or temporary.stat().st_size!=size:raise ValueError('SDK checksum/size mismatch')
    temporary.replace(archive)
    return archive


def run(args):
    print('Running '+Path(str(args[0])).name,flush=True)
    subprocess.run(list(map(str,args)),check=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path)
    args=parser.parse_args()
    BUILD.mkdir(parents=True,exist_ok=True)
    jdk=args.jdk or next((RUNTIME/'compile-check').glob('jdk-*'),None)
    if not jdk:raise RuntimeError('JDK17 required: pass --jdk path')
    java,javac,keytool=(jdk/'bin'/name for name in ('java.exe','javac.exe','keytool.exe'))
    if not java.exists():raise RuntimeError('JDK java.exe missing')
    platform=fetch('platform-35_r02.zip','https://dl.google.com/android/repository/platform-35_r02.zip',
                   '0bb560a90a7a2cbd0dd8348224d518b638fe7949',64273788)
    tools_archive=fetch('build-tools_r35_windows.zip','https://dl.google.com/android/repository/build-tools_r35_windows.zip',
                        'af059bb67cf7786f45ee0db85e2d24985df1b4b6',59878107)
    android_jar=BUILD/'android.jar'
    with zipfile.ZipFile(platform) as bundle:
        entry=next(n for n in bundle.namelist() if n.endswith('/android.jar') and n.count('/')==1)
        android_jar.write_bytes(bundle.read(entry))
    extracted=BUILD/'sdk-tools';extracted.mkdir(exist_ok=True)
    with zipfile.ZipFile(tools_archive) as bundle:
        for info in bundle.infolist():
            if not (extracted/info.filename).resolve().is_relative_to(extracted.resolve()):raise ValueError('unsafe SDK archive path')
        bundle.extractall(extracted)
    tools=next(extracted.rglob('aapt2.exe')).parent
    manifest=ET.parse(ROOT/'android/app/src/main/AndroidManifest.xml')
    root=manifest.getroot();root.set('package','org.dravet.guard.engineering.debug')
    root.set('{'+ANDROID_NS+'}versionCode','10');root.set('{'+ANDROID_NS+'}versionName','0.9-engineering')
    root.insert(0,ET.Element('uses-sdk',{'{'+ANDROID_NS+'}minSdkVersion':'26','{'+ANDROID_NS+'}targetSdkVersion':'35'}))
    application=root.find('application');application.set('{'+ANDROID_NS+'}debuggable','true');application.set('{'+ANDROID_NS+'}usesCleartextTraffic','true')
    for kind in ('activity','service'):
        for element in application.findall(kind):
            name=element.get('{'+ANDROID_NS+'}name','')
            if name.startswith('.'):element.set('{'+ANDROID_NS+'}name','org.dravet.guard'+name)
    ET.register_namespace('android',ANDROID_NS)
    merged=BUILD/'AndroidManifest.xml';manifest.write(merged,encoding='utf-8',xml_declaration=True)
    base=BUILD/'base.apk'
    compiled=BUILD/'resources.zip'
    run([tools/'aapt2.exe','compile','--dir',ROOT/'android/app/src/main/res','-o',compiled])
    run([tools/'aapt2.exe','link','-I',android_jar,'--manifest',merged,compiled,'-A',ROOT/'android/app/src/main/assets','-o',base])
    generated=BUILD/'BuildConfig.java';generated.write_text('package org.dravet.guard; public final class BuildConfig { public static final boolean DEBUG=true; }',encoding='utf-8')
    classes=Path(tempfile.mkdtemp(prefix='classes-',dir=BUILD))
    sources=list((ROOT/'android/app/src/main/java').rglob('*.java'))
    run([javac,'-encoding','UTF-8','--release','17','-cp',android_jar,'-d',classes,generated,*sources])
    class_jar=BUILD/'app-classes.jar'
    with zipfile.ZipFile(class_jar,'w') as bundle:
        for file in classes.rglob('*.class'):bundle.write(file,file.relative_to(classes).as_posix())
    dex=Path(tempfile.mkdtemp(prefix='dex-',dir=BUILD))
    run([java,'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android_jar,'--min-api','26','--output',dex,class_jar])
    unaligned=BUILD/'unaligned.apk';shutil.copy2(base,unaligned)
    with zipfile.ZipFile(unaligned,'a',zipfile.ZIP_DEFLATED) as bundle:
        for file in dex.glob('*.dex'):bundle.write(file,file.name)
    aligned=BUILD/'aligned.apk';run([tools/'zipalign.exe','-f','4',unaligned,aligned])
    keystore=BUILD/'engineering-debug.keystore'
    if not keystore.exists():
        run([keytool,'-genkeypair','-keystore',keystore,'-alias','androiddebugkey','-storepass','android','-keypass','android',
             '-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-keysize','2048','-validity','10000'])
    output=ROOT/'outputs/apk';output.mkdir(parents=True,exist_ok=True)
    apk=output/'DravetGuard-Prototype-0.9.apk'
    run([java,'-jar',tools/'lib/apksigner.jar','sign','--ks',keystore,'--ks-key-alias','androiddebugkey',
         '--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned])
    run([java,'-jar',tools/'lib/apksigner.jar','verify','--verbose',apk])
    run([tools/'zipalign.exe','-c','4',apk])
    badging=subprocess.check_output([str(tools/'aapt2.exe'),'dump','badging',str(apk)]).decode('utf-8',errors='replace')
    (output/'apk-badging.txt').write_text(badging,encoding='utf-8')
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix('.apk.sha256').write_text(digest+'  '+apk.name+'\n',encoding='utf-8')
    print(json.dumps({'apk':str(apk),'sha256':digest,'bytes':apk.stat().st_size},ensure_ascii=False),flush=True)


if __name__=='__main__':main()
