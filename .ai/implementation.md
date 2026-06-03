# impl.md — RESP test plan

## Step 1: Add JUnit 5 to `pom.xml`

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.11.4</version>
    <scope>test</scope>
</dependency>
```

## Step 2: Create test directory structure

```
src/test/java/dev/redish/resp/
    RespParserTest.java
```

## Step 3: RespParserTest cases

### Individual `@Test` methods

| Method | Input (RESP bytes) | Expected result |
|---|---|---|
| `parseSimpleString` | `+OK\r\n` | `"OK"` |
| `parseError` | `-ERR\r\n` | `"ERR"` |
| `parseInteger` | `:42\r\n` | `42L` |
| `parseIntegerNegative` | `:-1\r\n` | `-1L` |
| `parseBulkStringNull` | `$-1\r\n` | `null` |
| `parseEmptyArray` | `*0\r\n` | `[]` |
| `parseNullArray` | `*-1\r\n` | `null` |
| `parseArrayOfMixedTypes` | `*2\r\n+OK\r\n:42\r\n` | `["OK", 42L]` |
| `parseNestedArray` | `*1\r\n*2\r\n+A\r\n+B\r\n` | `[["A", "B"]]` |

### Parameterized test — `parseBulkString`

Uses `@CsvSource` (or `@MethodSource`):

| Input stream | Expected |
|---|---|
| `$5\r\nhello\r\n` | `"hello"` |
| `$0\r\n\r\n` | `""` (empty) |
| `$1\r\na\r\n` | `"a"` |
| `$11\r\nhello world\r\n` | `"hello world"` |

## Step 4: Verify

```bash
mvn test
```

All green.
