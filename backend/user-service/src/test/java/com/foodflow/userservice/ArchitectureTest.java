package com.foodflow.userservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the clean-architecture layering of every feature package:
 * {@code api -> application -> domain <- infrastructure}.
 */
@AnalyzeClasses(packages = "com.foodflow.userservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsIndependentOfOuterLayers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..infrastructure..")
            .because("the domain model is the core and must not know about use cases, web or adapters");

    @ArchTest
    static final ArchRule domainHasNoWebOrMessagingDependencies = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "jakarta.servlet..", "org.springframework.kafka..",
                    "org.springframework.security..", "com.foodflow.events..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..",
                    "org.springframework.web..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule apiDoesNotUseInfrastructure = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .because("controllers talk to use cases, never directly to persistence or messaging adapters");

    @ArchTest
    static final ArchRule userFeatureDoesNotDependOnAuthFeature = noClasses()
            .that().resideInAPackage("com.foodflow.userservice.user..")
            .should().dependOnClassesThat().resideInAPackage("com.foodflow.userservice.auth..")
            .because("auth builds on users, not the other way round (ports such as SessionRevoker invert it)");

    @ArchTest
    static final ArchRule featuresAreFreeOfCycles = slices()
            .matching("com.foodflow.userservice.(*)..")
            .should().beFreeOfCycles();
}
