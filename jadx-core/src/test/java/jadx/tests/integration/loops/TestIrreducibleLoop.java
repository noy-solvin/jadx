package jadx.tests.integration.loops;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestIrreducibleLoop extends SmaliTest {

	@Test
	public void test() {
		disableCompilation();
		allowWarnInCode();
		assertThat(getClassNodeFromSmali())
				.code()
				.containsOne("public static int test(int ");
	}
}
