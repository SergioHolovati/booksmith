package com.booksmith.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppApplicationTests {

	@Test
	void applicationClassShouldExist() {
		assertThat(AppApplication.class).isNotNull();
	}

}
