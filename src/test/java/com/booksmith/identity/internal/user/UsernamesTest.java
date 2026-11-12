package com.booksmith.identity.internal.user;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UsernamesTest {

    @Test
    void comesFromTheNameNeverFromTheEmail() {
        assertThat(Usernames.suggest("Lia Moreau", name -> false)).isEqualTo("lia.moreau");
        assertThat(Usernames.suggest("  Sérgio  D'Ávila Júnior ", name -> false)).isEqualTo("sergio.d.avila.junior");
    }

    @Test
    void takenOrEmptyGetsFourDigits() {
        assertThat(Usernames.suggest("Lia Moreau", Set.of("lia.moreau")::contains)).matches("lia\\.moreau\\.\\d{4}");
        assertThat(Usernames.suggest(null, name -> false)).matches("autor\\.\\d{4}");
        assertThat(Usernames.suggest("!!!", name -> false)).matches("autor\\.\\d{4}");
    }

    @Test
    void staysShort() {
        String name = Usernames.suggest("Maria da Conceição dos Santos Albuquerque Pereira", name1 -> true);
        assertThat(name.length()).isLessThanOrEqualTo(Usernames.MAX_LENGTH);
        assertThat(name).startsWith("maria.da.conceicao").doesNotContain("..");
    }
}
