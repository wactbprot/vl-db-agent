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

The old system allows to write multiple results to one path:

```shell
D='{"DocPath": "Calibration.Measurement.Values.Temperature",
    "Results":[{"Value":23.1, "Type":"ch1", "Unit":"C"},
	           {"Value":23.2, "Type":"ch2", "Unit":"C"},
			   {"Value":23.3, "Type":"ch3", "Unit":"C"}]}'
```

**vl-docsrv** accepts a `DocPaths` key provides a way to write results to multiple pathes:

```shell
D='{"DocPaths": ["Calibration.Measurement.Values.Pressure",
                 "Calibration.Measurement.Values.Slope"],
    "Results":[{"Value":1e-6, "Type":"ind",      "Unit":"DCR"}, 
	           {"Value":1e-8, "Type":"ind_rise", "Unit":"DCR/s"}]}'
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
