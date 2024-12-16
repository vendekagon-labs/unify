## Current: Diff

[X] Identify source of the `nil` in retract UID listing. Is it a problem?
  - It's NeoAntigen, technically we should require that diff and retract validate
    schema w/r/t "all kinds have an acceptable UID" -- I don't want to enforce this
    for all users of Unify necessarily, but this is required for diff/merge and
    (retract? as implemented).

[X] See if we can sort UID list from retract with diff/merge sort for ref ordering.

Yes, looks like we can!

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

