VERSION=$(shell util/version)

repl:
	clj -X:repl-server :port 5555

version-info: clean
	echo "{:pret/version \"$(VERSION)\"}" > resources/info.edn

cache-schema:
	clj -M -m com.vendekagonlabs.unify.db.schema.cache

uberjar: version-info cache-schema
	clj -Mdepstar target/pret.jar

package-prod: uberjar
	mkdir target/pret-$(VERSION)
	mkdir target/pret-$(VERSION)/env
	cp target/pret.jar target/pret-$(VERSION)/.
	cp pret target/pret-$(VERSION)/.
	cp pretw.bat target/pret-$(VERSION)/.
	cd target && \
		zip -r pret-$(VERSION).zip pret-$(VERSION)

clean:
	mkdir -p target
	rm -rf target/*
