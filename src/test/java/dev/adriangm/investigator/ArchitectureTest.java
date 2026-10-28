package dev.adriangm.investigator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    @Test
    void dependenciesPointInwards()
    {
        var classes = new ClassFileImporter().importPackages("dev.adriangm.investigator");
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("..domain..", "java..")
            .check(classes);
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("..application..", "..domain..", "java..")
            .check(classes);
        noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter.out..", "..bootstrap..")
            .check(classes);
        noClasses()
            .that()
            .resideInAPackage("..adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter.in..", "..bootstrap..")
            .check(classes);
        noClasses()
            .that()
            .resideOutsideOfPackage("..bootstrap..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..bootstrap..")
            .check(classes);
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain..")
            .and()
            .areNotStatic()
            .should()
            .bePrivate()
            .check(classes);
    }
}
