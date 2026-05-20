Sample `.jfr` files for testing go here. Not committed (see `.gitignore`).

To generate one, run:

```
./gen-sample.sh
```

This produces `samples/sample.jfr` from a short allocation/CPU workload
recorded under the `profile` JFR settings preset. Requires Java 25+ on PATH.
