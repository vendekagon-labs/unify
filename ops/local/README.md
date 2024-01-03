# Local Ops

The local ops system uses docker-compose to stand up a basic Datomic postgres
backed service.

## Required Setup

You will need to download a recent version of [Docker](https://docs.docker.com/get-docker/)
for your platform, which should have `docker-compose` bundled with it.

For local development and running Unify outside of compose, you might want to
alias `transactor` to 127.0.0.1 in your `/etc/hosts` file.

## Starting and Stopping the local dev system

You can start or stop the system with the wrapping scripts in `util/`

```
util/start-local-system
util/stop-local-system
```

## Notes

This work was derived from the example
[here](https://github.com/galuque/datomic-compose),
though it has been substantially simplified. It also uses a PostGIS container,
for planned future support for GIS/geometry/shape information in a Unify blob
format.
