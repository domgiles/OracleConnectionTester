# Oracle Connection Tester
A simple java connection tester to run against either local or databases running in the Oracle Cloud using credential files.

It has the following parameters
```
usage: parameters:
 -cf <zipfile>         credentials file in zip format
 -cs <connectstring>   connect string
 -ct <threadcount>     pds or ods
 -debug                turn on debugging. Written to standard out
 -dt <driver_type>     Driver Type [thin,oci]
 -o <output>           output : valid values are stdout,csv
 -p <password>         password
 -tc <threadcount>     thread count, defaults to 1
 -u <username>         username
```

