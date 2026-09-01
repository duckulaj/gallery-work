package com.hawkins.gallery.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;

class LayerBoundaryTest {

    @Test
    void webControllersDoNotReachIntoPersistenceRepositories() {
        var classes = new ClassFileImporter().importPackages("com.hawkins.gallery");
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .check(classes);
    }
}
