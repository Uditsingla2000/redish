# impl.md — refactor: ErrorResponse record

## problem

- writeError is public — bad encapsulation
- dispatch catches exception for errors — two error paths (exception + writeError)
- UnknownCommand throws just to be caught by dispatch

## solution: ErrorResponse record

### new file

```
src/main/java/dev/redish/resp/ErrorResponse.java
```

```java
public record ErrorResponse(String message) {}
```

### RespSerializer changes

1. revert writeError back to private
2. add to serialize():
   ```java
   if (obj instanceof ErrorResponse e) {
       writeError(e.message(), out);
       return;
   }
   ```

### Command changes

- **PingCommand**: return `new ErrorResponse("ERR wrong number of arguments for 'PING' command")` instead of throw
- **UnknownCommand**: return `new ErrorResponse("ERR unknown command '" + cmd + "'")` instead of throw

### Server.java changes

- remove try/catch for IllegalArgumentException
- single path: `cmd.execute(args)` → `RespSerializer.serialize(result, out)`
- ErrorResponse auto-routes to error wire format

### test updates

- PingCommandTest: pingTooManyArgs checks return value (not exception)
- CommandRegistryTest: unknownResponse checks return value (not exception)
- RespSerializerTest: remove writeError public test (no longer public)
- add RespSerializerTest: serialize ErrorResponse → `-ERR ...\r\n`

## before vs after (server dispatch)

**before:**
```
try {
    Object result = cmd.execute(args);
    RespSerializer.serialize(result, out);
} catch (IllegalArgumentException e) {
    RespSerializer.writeError(e.getMessage(), out);
}
```

**after:**
```
Object result = cmd.execute(args);
RespSerializer.serialize(result, out);
```

## before vs after (command)

**before:** `throw new IllegalArgumentException("ERR ...")`

**after:** `return new ErrorResponse("ERR ...")`
