import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
public class RunSpecs {
  public static void main(String[] args) {
    var builder = LauncherDiscoveryRequestBuilder.request();
    for (String name : args) builder.selectors(DiscoverySelectors.selectClass("run.endive.test.gen." + name));
    var listener = new SummaryGeneratingListener();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(builder.build());
    listener.getSummary().printTo(new PrintWriter(System.out));
    listener.getSummary().printFailuresTo(new PrintWriter(System.out));
    if (listener.getSummary().getTotalFailureCount() > 0) System.exit(1);
  }
}
