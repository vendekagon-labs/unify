# Unify

Unify is a tool for data harmonization. In simple, technical terms,
it automates batch imports into Datomic and an object storage such as
S3. Unify is built on foundational tooling derived from
[CANDEL](https://github.com/CANDELBio), extracted and made more generic.

Unify's data harmonization tools are schema agnostic. That is, they can
work on schemas for many different domains, provided some annotations are
added to the schema as per Unify's metamodel.

Unify is under heavy development and should be considered early alpha.

## Unify Concepts

Unify uses schema annotation in a Datomic database, called the "metamodel", to
infer the details necessary for automating a batch import process. The combined
schema and metamodel can be defined in a Unify specified DSL, or it can be
transacted more verbosely as raw Datomic edn.

Once the schema annotations are in place, an import can be defined declaratively
in a config.edn file that specifies how to map data from external files into the schema.
Datomic handles reference resolution by resolving unique identifying fields into values.
In Unify, these can be defined a number of ways: as globally unique identifiers, as only
unique in the context of a tree of data relationships, or as composite IDs composed of other fields
that is only unique in composite form.

End-to-end examples and tutorials are planned which will provide a more detailed
walkthrough of how to use Unify. Example data is available in this repo in the
`test/resources/systems/` subdirectory.

There is more thorough documentation for a specific system configuration,
UnifyBio (as used by the Rare Cancer Research Foundation and Vendekagon Labs),
available [here](https://unifybio.github.io/). The
[detailed docs on import config](https://unifybio.github.io/import-config/)
are especially helpful for new users of any Unify system.

## Local Setup

For running the Unify CLI, install the latest version of
[Clojure](https://clojure.org/guides/install_clojure).

Unify provides a simple local system setup for test use that only requires
`docker-compose`. You can inspect the local system and change the configuration,
or use it as a template. It can be found in the repo at `ops/local/`. To
get going with minimal fuss, with `docker compose`
[installed](https://docs.docker.com/desktop/install/), run the following from
the project root:

```
util/start-local-system
```

You can terminate the system with:

```
util/stop-local-system
```

## How to Use

After standing up a local system, you can run:

```
./unify-local
```

with no arguments for command line help.

### Using the example CANDEL template dataset

To stand up an example db with the
CANDEL schema, run the following commands with these values set:

```
$DATABASE_NAME  # a string, the name of the database
$SCHEMA_DIR  # a directory containing a unify schema
$WORKING_DIR  # user defined output path, ./unify prepare will create this
$IMPORT_CONFIG_PATH  # path to a config edn file describing an import
$SEED_DATA_DIR  # a path to initial reference/seed data, e.g. HGNC names for genes
```

The example CANDEL parameters can be run from this repo with no additions:

```
$DATABASE_NAME=unify-example
$SCHEMA_DIR=test/resources/systems/candel/template-dataset/schema
$WORKING_DIR=~/scratch/candel-import-prepared
$IMPORT_CONFIG_PATH=test/resources/systems/candel/template-dataset/config.edn
$SEED_DATA_DIR=test/resources/systems/candel/reference-data
```

The steps are (1) request a db (this will create & transact schema + seed data).

```
./unify-local request-db --database unify-example \
                         --schema-directory $SCHEMA_DIR \
                         --seed-data-directory $SEED_DATA_DIR
```

Then (2) prepare the import. This reads the files linked in the config.edn file
and turns them into Datomic transaction data as per the import specification in
said file.

```
./unify-local prepare --working-directory $WORKING_DIR \
                      --schema-directory $SCHEMA_DIR \
                      --import-config $IMPORT_CONFIG_PATH
```

Once the data has been prepared, then for step (3) transact it into the database:

```
./unify-local transact --working-directory $WORKING_DIR \
                       --database $DATABASE_NAME
```

### Local Schema Browsing and Query

You can use a graphic browser to explore the schema in your example database at:

```
http://localhost:8899/schema/1.3.1/index.html
```

You can also query it by connecting to it from a normal Datomic peer process,
or from any other language using the JSON query service that the local ops
stands up with root URL of:

```
http://localhost:8889/query/$DB_NAME
```

There's an example Python query that can be run in isolation on the example
CANDEL database with:

```
pip install requests  # if needed
python ops/local-test-query.py
```

You can inspect this file for an example of working with the query service.

## Tutorials and Examples

Stay tuned for more!

## License

Apache 2.0, Copyright Vendekagon Labs, LLC.

### Supporters

Development on Unify is currently being supported by the
[Rare Cancer Research Foundation](https://rarecancer.org). Past development has
been supported by [Clojurists Together](https://www.clojuriststogether.org).

Unify was developed from CANDEL, which was open sourced by the
[Parker Institute for Cancer Immunotherapy](https://www.parkerici.org/).
