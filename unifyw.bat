@echo off
java -cp pret.jar -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 clojure.main -m com.vendekagonlabs.unify.cli %*
