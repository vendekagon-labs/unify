VERSION=$(shell util/version)

repl:
	clj -X:repl-server :port 5555

version-info: clean
	echo "{:unify/version \"$(VERSION)\"}" > resources/info.edn

uberjar: version-info cache-schema
	clj -Mdepstar target/unify.jar

package-prod: uberjar
	mkdir target/unify-$(VERSION)
	mkdir target/unify-$(VERSION)/env
	cp target/unify.jar target/unify-$(VERSION)/.
	cp unify target/unify-$(VERSION)/.
	cp unifyw.bat target/unify-$(VERSION)/.
	cd target && \
		zip -r unify-$(VERSION).zip unify-$(VERSION)

clean:
	mkdir -p target
	rm -rf target/*
