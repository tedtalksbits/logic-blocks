#!/bin/bash
# Boot a throwaway Paper server with Logic Blocks + the self-test; print the report.
cd /home/claude/paper2
mkdir -p plugins
TS=$(date +%s); [ -d world ] && mv world old-world-$TS
cp plugins/spike.jar old-spike-$TS.jar 2>/dev/null && mv plugins/spike.jar /home/claude/paper2/old-spike.jar 2>/dev/null
cp /home/claude/lb/logic-plugin/build/libs/logic-plugin-0.1.0.jar plugins/LogicBlocks.jar
cp /home/claude/lb/paper-selftest/build/libs/paper-selftest-0.1.0.jar plugins/LogicSelfTest.jar
timeout 300 /usr/lib/jvm/java-25-openjdk-amd64/bin/java -Xmx2G -Dlogic.selftest.exit=true -jar paper-26.2-130.jar --nogui > selftest.log 2>&1
grep -E "ERROR.*(Logic|selftest)|Exception.*whizzle" selftest.log | head -5
cat plugins/LogicSelfTest/report.txt
