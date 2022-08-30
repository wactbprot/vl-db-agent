vl-db-agent
---------

Metis and DevProxy both write data to vaclab style calibration
documents. There is a small chance that conflicts occur due to
uncoordinated writing.  vl-db-agent provides an API for coordinated
writing of vaclab style measurement results to calibration documents.

[⇨ documentation](https://a75438.berlin.ptb.de/vl-db-agent/docs/uberdoc.html)
[⇨ jar](https://a75438.berlin.ptb.de/vl-db-agent/)

## Usage

Set (shell):

```shell
H="Content-Type: application/json"
URL=http://localhost:9992

```

and give some data with `Result` and a `DocPath`:

```shell
D='{"DocPath": "Calibration.Measurement.Values.Pressure", "Result":[{"Value":100, "Type":"ind", "Unit":"Pa"}]}'
```

use e.g. `curl`:

```shell
curl -H "$H" -d "$D" -X POST $URL/cal-2022-se3-pn-4025_0007 --noproxy "*"
```

The old system allows to write multiple results to one path:

```shell
D='{"DocPath": "Calibration.Measurement.Values.Temperature",
    "Result":[{"Value":23.1, "Type":"ch1", "Unit":"C"},
	           {"Value":23.2, "Type":"ch2", "Unit":"C"},
			   {"Value":23.3, "Type":"ch3", "Unit":"C"}]}'
```

**vl-db-agent** accepts  `DocPath` to be a vector which provides a way to write results to multiple pathes:

```shell
D='{"DocPath": ["Calibration.Measurement.Values.Pressure",
                 "Calibration.Measurement.Values.Slope"],
    "Result":[{"Value":1e-6, "Type":"ind",      "Unit":"DCR"},
	           {"Value":1e-8, "Type":"ind_rise", "Unit":"DCR/s"}]}'
```

## Generate api docs

copy `vendor` folder from [marginalia](https://github.com/wactbprot/marginalia) to `resources` folder. Next run:


```shell
clojure -M:docs
```


upload:

```shell
scp -r docs/ bock04@a75438://var/www/html/vl-db-agent/
```


### Build uberjar

```shell
clj -T:build all
```


upload:

```shell
scp -r target/vl-db-agent-x.y.z.jar bock04@a75438://var/www/html/vl-db-agent
```
