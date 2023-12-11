- Include basic Datomic local system topology via Docker/compose
- Simplify metamodel, esp. re: need-uid, etc.
- Documentation!!! esp README.md
- Move candel to its own sysytem in test resources
- Move dataset processing to scripts that can be run end-to-end in a test
  preprocessing namespace.
- Move reference/bootstrap data and re-organize to make it schema specific.
- Create more compact, edn friendly, user friendly way to define bootstrap reference data.
- CLI arg for request-db to point to bootstrap reference data
- Why does prepare slow down when pret is run twice at the REPL? Can we identify and fix this?
- Variant fixes: revert back to original variant_ref files, maybe?

