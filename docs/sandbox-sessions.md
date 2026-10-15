# Sandbox sessions and files

`SandboxClient` manages reusable AgentKit Code Sandbox sessions. A stable logical session ID
reuses the same live remote environment; the returned `SandboxSession` exposes its physical ID,
status, endpoint, creation time, and expiration time.

```java
import com.volcengine.veadk.tools.builtin.sandbox.SandboxClient;

try (SandboxClient client = new SandboxClient()) {
    var session = client.createSession("order-analysis", 1800);
    session.writeFile("input/orders.csv", csvBytes);
    String result = session.runCode(
            "import pandas as pd; print(pd.read_csv('/workspace/input/orders.csv').sum())",
            "python3",
            60);
    byte[] report = session.readFile("output/report.json");
    session.close(); // deletes the remote physical session
}
```

The client also supports `getSession`, `listSessions`, and explicit session close. File methods
support UTF-8 or binary writes, bounded reads, recursive listing, and deletion. Paths are always
relative to `/workspace`; absolute paths and traversal are rejected locally and checked again in
the remote process, including resolved symlink targets. Individual reads and writes are limited to
10 MiB by default.

The default client reads the existing AgentKit endpoint, region, credentials, and code tool ID
configuration. Closing `SandboxClient` releases its local HTTP resources but does not implicitly
delete every session; close each `SandboxSession` when its remote environment is no longer needed.
