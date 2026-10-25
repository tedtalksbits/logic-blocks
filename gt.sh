#!/bin/bash
# Build + test logic-blocks offline; print failures and totals.
cd /home/claude/lb
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 GRADLE_USER_HOME=/home/claude/tools/gradle-home
timeout 900 /home/claude/tools/gradle/gradle-9.1.0/bin/gradle --offline ${@:-build} --console=plain -q 2>&1 | grep -v -e "^WARNING" -e "Picked up" -e "Sharing is only" | grep -E "error:|warning:.*java|FAIL|BUILD|\.java:" | head -40
python3 - <<'PY'
import glob,xml.etree.ElementTree as E
t=f=e=s=0
for p in glob.glob('/home/claude/lb/logic-plugin/build/test-results/test/*.xml'):
    r=E.parse(p).getroot(); t+=int(r.get('tests'));f+=int(r.get('failures'));e+=int(r.get('errors'));s+=int(r.get('skipped'))
    for c in r.iter('testcase'):
        for k in ('failure','error','skipped'):
            x=c.find(k)
            if x is not None:
                tb=[l.strip() for l in (x.text or '').splitlines() if 'whizzle' in l][:3]
                print(k.upper(), c.get('classname').split('.')[-1]+'.'+c.get('name'),'|',(x.get('message') or '')[:300],'|',' ; '.join(tb))
print(f"TOTAL {t} tests, {f} failed, {e} errors, {s} skipped")
PY
