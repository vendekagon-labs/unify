version-info:
	clj -X:build write-version-info!

repl: version-info
	clj -X:repl-server :port 5555

uberjar: clean
	clj -X:build

clean:
	mkdir -p target
	rm -rf target/*
