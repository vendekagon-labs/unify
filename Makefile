VERSION = "$(shell clj -X:version)"

version-info:
	echo "{:unify/version \"${VERSION}\"}" > resources/info.edn

repl: version-info
	clj -X:repl-server :port 5555

uberjar: clean version-info
	clj -X:build

clean:
	mkdir -p target
	rm -rf target/*
