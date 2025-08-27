# Obsinity Java SDK — Build & Test Guide

## Prerequisites

* **Java 17** (Azul, Temurin, Oracle—all fine)
* **Maven 3.9+**
* Internet access to resolve dependencies

## Project Coordinates

* **Group:** `com.obsinity.telemetry`
* **Artifact:** `obsinity-java-sdk`
* **Version:** `0.0.2-SNAPSHOT`
* **Packaging:** `jar`

## Key Tech & Version Management

* Spring Boot parent: **3.5.4**
* OpenTelemetry via BOM: **1.53.0**
* Jackson: **2.19.2**
* Lombok: **1.18.38**
* Spotless (Palantir Java Format): **2.59.0** (formatter), plugin **2.43.0**
* PIT (mutation testing): **1.17.0** + JUnit5 plugin **1.2.1**
* JUnit Jupiter / Platform pinned (to avoid launcher mismatch): **5.11.3 / 1.11.3**
* Surefire: **3.2.5** (JPMS module path disabled in PIT profile)

## Common Tasks

### 1) Clean compile, run unit tests

```bash
mvn clean verify
```

### 2) Build the JAR (tests included)

```bash
mvn clean package
# output: target/obsinity-java-sdk-0.0.2-SNAPSHOT.jar
```

Skip tests if needed:

```bash
mvn -DskipTests clean package
```

### 3) Code style & formatting

Spotless runs **automatically** at `validate` (fails build on violations).

* Check only (already bound): runs on every build
* Auto-fix formatting:

```bash
mvn spotless:apply
```

Formatting rules:

* Palantir Java Format (`style=PALANTIR`, Javadoc formatting enabled)
* Tabs enabled (`spacesPerTab=4`)
* Import order: `java|jakarta, lombok|org, |com.obsinity`
* Remove unused imports + trailing whitespace
* Regex rule removes wildcard imports

### 4) Mutation testing (PIT)

Activate the **pitest** profile to generate mutation coverage:

```bash
mvn -Ppitest clean verify
```

Configuration highlights:

* **Thresholds:** `mutationThreshold=33`, `coverageThreshold=62`
  (build fails if below)
* **Mutators:** `STRONGER`
* **Targets:**

    * Classes: `com.obsinity.telemetry.*`
    * Tests: `com.obsinity.telemetry.*Test`, `*Tests`
* **JPMS:** module path disabled for tests (`useModulePath=false`)
* **JVM opens** added for JDK17 reflective access
* **Output:** HTML + XML reports → `target/pit-reports/<timestamp>/index.html`

### 5) Running specific tests

```bash
mvn -Dtest=SomeTest test
mvn -Dtest=SomeTest#methodName test
```

## Dependency Management

* OpenTelemetry dependencies are managed via the **OTel BOM**:

```xml
<dependencyManagement>
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-bom</artifactId>
    <version>${otel.version}</version>
    <type>pom</type>
    <scope>import</scope>
  </dependency>
</dependencyManagement>
```

You can add additional OTel components without specifying versions.

## IDE Setup Tips

* Enable **annotation processing** for Lombok.
* Use **tabs** with width 4 for local formatting (to match Spotless).
* If the IDE flags `<testPlugin>junit5</testPlugin>` in PIT config: it’s fine—Maven accepts it; IDE schema just doesn’t know the plugin. (In this POM, PIT also works by auto-detecting JUnit5 via its plugin dep.)

## Troubleshooting

**PIT “OutputDirectoryProvider not available” or JUnit launcher errors**

* This POM pins JUnit Jupiter/Platform to aligned versions and adds `pitest-junit5-plugin`; re-run:

  ```bash
  mvn -Ppitest clean verify
  ```
* Check `target/pit-reports/*/messages.log` for the topmost cause.

**Reflective access / IllegalAccessError on Java 17**

* The pitest profile includes `--add-opens` for common JDK packages. If you see a new package in the error, add a matching `--add-opens=...` in the PIT `jvmArgs`.

**Formatting failures (Spotless)**

* Run `mvn spotless:apply` to auto-fix, then rebuild.

**Wildcard imports keep reappearing**

* Spotless regex strips wildcard imports; ensure your IDE import settings are aligned, or re-run `spotless:apply`.

## What’s *not* included

* No Spring Boot repackage/exec plugin (this is a library JAR, not an app).
* No JaCoCo configuration (only PIT mutation reports).

---