package com.booksmith;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * Regras de arquitetura que o Spring Modulith não cobre (ele cuida das fronteiras entre módulos; aqui ficam as
 * camadas dentro de cada módulo e hábitos de código limpo). Cada regra diz o porquê, para quem a quebrar entender.
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.booksmith");
    }

    @Test
    void controllersTalkToServicesNeverToRepositories() {
        noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().areAssignableTo(Repository.class)
                .because("a regra de negócio (dono, validação, lixeira) mora no serviço; o controller só traduz HTTP")
                .check(classes);
    }

    @Test
    void serviceLayerNeverDependsOnTheWebLayer() {
        noClasses().that().resideInAPackage("com.booksmith..internal..")
                .should().dependOnClassesThat().resideInAPackage("com.booksmith..web..")
                .because("o serviço não sabe que existe HTTP: assim dá para chamá-lo de um job, de um evento ou de um teste")
                .check(classes);
    }

    @Test
    void controllersLiveInTheWebPackage() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..web..")
                .because("cada módulo tem a porta de entrada HTTP num lugar só")
                .check(classes);
    }

    @Test
    void entitiesStayInsideTheirModule() {
        classes().that().areAnnotatedWith(Entity.class)
                .should().resideInAPackage("..internal..")
                .because("outro módulo fala pela API pública, nunca pela tabela de outro")
                .check(classes);
    }

    @Test
    void constructorInjectionOnly() {
        fields().should().notBeAnnotatedWith(Autowired.class)
                .because("dependência no construtor (final) deixa claro do que a classe precisa e facilita o teste")
                .check(classes);
    }

    @Test
    void logsGoThroughSlf4j() {
        NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because("System.out some em produção; use o log").check(classes);
        NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because("um log só (SLF4J), com o trace id da requisição").check(classes);
    }
}
