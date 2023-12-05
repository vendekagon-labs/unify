#!/bin/bash
VERSION="0.5.0"
FILES=*.edn
for f in $FILES
do
    aws s3 cp $f s3://pret-processed-reference-data-staging/$VERSION/$f
done

