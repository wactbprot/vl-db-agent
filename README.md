vl-docsrv
---------

Metis and DevProxy both write data to vaclab style calibration
documents. There is a small chance that conflicts occur due to
uncoordinated writing.  vl-docsrv provides an API for coordinated
writing of vaclab style measurement results to calibration documents.

[⇨ documentation](https://a75438.berlin.ptb.de/vl-docsrv/docs/uberdoc.html)

## Usage

Set (shell):

```shell
H="Content-Type: application/json"
URL=http://localhost:9992

```

and give some data with `Results` and a `DocPath`:

```shell
D='{"DocPath": "Calibration.Measurement.Values.Pressure", "Results":[{"Value":100, "Type":"ind", "Unit":"Pa"}]}'
```

use e.g. `curl`:

```shell
curl -H "$H" -d "$D" -X POST $URL/cal-2022-se3-pn-4025_0007 --noproxy "*"
```



## Generate api docs

copy `vendor` folder from [marginalia](https://github.com/wactbprot/marginalia) to `resources` folder. Next run:


```shell
clojure -X:dev:docs
```


upload:

```shell
scp -r docs/ bock04@a75438://var/www/html/vl-docsrv/
```
