import os
import gzip as gz
import sys
import json

# pip install requests
import requests

# A json file containing a query map nested in a request map can
# be passed as the first argument to the script.:
# {"query": {":find" ...}}
if len(sys.argv) > 1:
    with open(sys.argv[1], 'r') as f:
        req_body = json.load(f)
    q = req_body["query"]

# Otherwise, minimal default query counts number of patients in the database.
else:
    print("Using default query")
    q = {":find": [["count", "?m"]],
         ":where": [["?m", ":measurement/gene-product"]]}
    req_body = {"query": q,
                "timeout": 30000}

# parameters needed to issue the query request (POST) 
endpoint = os.getenv('QUERY_SERVICE_ENDPOINT') or \
           "http://localhost:8988/query/unify-example"
bearer_token = os.getenv('BEARER_TOKEN') or "dev"
headers = {
        "Authorization": f"Bearer {bearer_token}",
        "Accept": f"application/json",  # application/json returns un-cached json
        #"Accept": f"text/plain"  # text/plain returns presigned s3 url
}
print("Querying querl URL:", endpoint)
print("With query:\n")
print(json.dumps(q, indent=1))

resp = requests.post(endpoint, json.dumps(req_body), headers=headers,
                     )
print("Received response, downloading and unzipping.")

if resp.status_code == 200:
    print(resp.content)
    if resp.headers['content-type'] == 'text/plain':
        resp2 = requests.get(resp.content)
        body = json.loads(gz.decompress(resp2.content))
    else:
        body = json.loads(resp.content)
    print(body['query_result'])
    print(f"Basis T of query results: {body['basis_t']}")
else:
    print("Encountered an error.")
    print(resp.status_code)
    try:
        print(resp.text)
    except:
        print("Failed to decode response body.")

