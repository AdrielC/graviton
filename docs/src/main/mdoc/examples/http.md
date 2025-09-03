# HTTP Gateway Example

Assuming the gateway is running on `localhost:8080`:

```bash
# upload a file
$ curl -X POST --data-binary @README.md http://localhost:8080/files
{"fileKey":"..."}

# download the stored file
$ curl http://localhost:8080/files/<fileKey> -o README.copy.md
```
