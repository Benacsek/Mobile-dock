## Contributing

- Keep the Android app free of third-party libraries. It must build with minSdk 16.
- Any change to the wire format must land in Python, Java and docs/PROTOCOL.md together. Bump the protocol version if old apps would break.
- Run the host tests before you push:

```bash
python -m pytest host/tests
```

- CI builds the APK, runs Android lint and runs the Java interop test.
