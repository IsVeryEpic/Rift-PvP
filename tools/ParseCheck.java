import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parse-only syntax check. Does NOT compile against Paper or verify runtime APIs. */
public final class ParseCheck {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass source directory");
    List<Path> source;
    try (var paths = Files.walk(Path.of(args[0]))) {
      source = paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      var input = files.getJavaFileObjectsFromPaths(source);
      JavacTask parseTask = (JavacTask) compiler.getTask(null, files, diagnostics,
          Arrays.asList("-source", "21", "-proc:none"), null, input);
      parseTask.parse();
    }
    var errors = diagnostics.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).collect(Collectors.toList());
    errors.forEach(System.err::println);
    System.out.println("Parsed " + source.size() + " Java files; syntax errors: " + errors.size());
    if (!errors.isEmpty()) System.exit(1);
  }
}
