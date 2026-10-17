# Vendored dependencies

`maven/` is a local Maven repository holding **JADE 4.6.0** (`com.tilab.jade:jade`).

JADE is not published to Maven Central, and its upstream repository
(https://jade.tilab.com/maven/) presents a certificate chain that the JVM does not trust by default,
so builds fail on a clean machine. The jar and pom are copied unmodified from that repository.

JADE is © Telecom Italia S.p.A. and distributed under the GNU LGPL v2.1: https://jade.tilab.com/
