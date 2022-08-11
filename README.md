vl-docsrv
---------

Metis and DevProxy both write data to vaclab style calibration
documents. There is a small chance that conflicts occur due to
uncoordinated writing.  vl-docsrv provides an API for coordinated
writing of vaclab style measurement results to calibration documents.



Set

```shell
H="Content-Type: application/json"
URL=http://localhost:9992

```


and

```shell
D='{"DocPath": "Calibration.Measurement.Values.Pressure", "Results":[{"Value":100, "Type":"ind", "Unit":"Pa"}]}'
```



and use it this way




```shell
curl -H "$H" -d "$D" -X POST $URL/cal-2022-se3-pn-4025_0007 --noproxy "*"
```
