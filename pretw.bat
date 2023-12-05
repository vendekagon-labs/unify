@echo off
echo "Running pret"

java -cp pret.jar -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 clojure.main -m org.candelbio.pret.cli %*
