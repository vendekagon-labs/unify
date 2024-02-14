## Current: Diff

- Fix schema/db global uncoordinated access and atoms.
- Fix/unify the way that the list of UIDs is generated, as this needs to also
  include global IDs, and should be the same as the UID indexing used in 
  retract.
- Pursue restoring diff functionality via existing code with fixed UID list.
- If that doesn't work, begin dissecting units of functionality, starting
  with the diff/changes namespace.

## Backlog from prior work

- Fix reference data fail on sequence ontology thing
- Why does prepare slow down when pret is run twice at the REPL? Can we identify and fix this?
- Variant fixes: revert back to original variant_ref files, maybe?

