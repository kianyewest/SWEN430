

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import whilelang.compiler.WhileCompiler;
import whilelang.util.SyntaxError;
import whilelang.util.SyntaxError.InternalFailure;

@RunWith(Parameterized.class)
public class NullInvalidTests {
private static final String WHILE_SRC_DIR = "test_files/invalid/null/".replace('/', File.separatorChar);

	private final String testName;

	public NullInvalidTests(String testName) {
		this.testName = testName;
	}

	/**
	 * Run the compiler over the test case. If it's an invalid test, then we
	 * expect it to fail. Otherwise, we expect it to pass.
	 *
	 * @param filename
	 * @throws IOException
	 */
	private void runTest(String testname) throws IOException {
		// Second, run type checker over the AST. We expect every test to
		// throw an error here. So, tests which do should pass, tests which
		// don't should fail.
		try {
			File srcFile = new File(WHILE_SRC_DIR + testname + ".while");
			WhileCompiler compiler = new WhileCompiler(srcFile.getPath());
			compiler.compile();
			fail();
		} catch (InternalFailure e) {
			throw e;
		} catch (SyntaxError e) {
			String expected = testname.split("-")[1];
			String code = e.code();
			
			assertTrue("Expected '" + expected + "' but found '" + code + "'", expected.equals(code));
			
			// Success!
			e.outputSourceError(System.err);
		}
	}

	// ===============================================================
	// Test Harness
	// ===============================================================

	// Here we enumerate all available test cases.
	@Parameters(name = "{0}")
	public static Collection<Object[]> data() {
		ArrayList<Object[]> testcases = new ArrayList<>();
		for (File f : new File(WHILE_SRC_DIR).listFiles()) {
			if (f.isFile()) {
				String name = f.getName();
				if (name.endsWith(".while")) {
					// Get rid of ".while" extension
					String testName = name.substring(0, name.length() - 6);
					testcases.add(new Object[] { testName });
				}
			}
		}
		// Sort the result by filename
		Collections.sort(testcases, new Comparator<Object[]>() {
			@Override
			public int compare(Object[] o1, Object[] o2) {
				return ((String) o1[0]).compareTo((String) o2[0]);
			}
		});
		return testcases;
	}

	@Test
	public void valid() throws IOException {
		runTest(this.testName);
	}
}
