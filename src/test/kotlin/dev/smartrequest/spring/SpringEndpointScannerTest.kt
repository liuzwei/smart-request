package dev.smartrequest.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.editor.SmartRequestLineMarkerProvider
import dev.smartrequest.model.BodyType

class SpringEndpointScannerTest : BasePlatformTestCase() {

    fun testScansControllerMappingsAndShortcutAnnotations() {
        addSpringStubs()
        myFixture.addFileToProject(
            "com/example/DemoController.java",
            """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestMethod;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api")
            public class DemoController {
                @GetMapping("/users/{id:\\d+}")
                public String user() {
                    return "";
                }

                @RequestMapping(value = "/create", method = RequestMethod.POST)
                public String create() {
                    return "";
                }
            }
            """.trimIndent()
        )

        val result = ReadAction.compute<SpringScanResult, RuntimeException> {
            SpringEndpointScanner.scan(project)
        }

        assertTrue(result.springDetected)
        assertEquals(
            setOf("GET /api/users/{id}", "POST /api/create"),
            result.endpoints.map { it.displayName }.toSet()
        )
    }

    fun testReportsMissingSpringClasspath() {
        val result = ReadAction.compute<SpringScanResult, RuntimeException> {
            SpringEndpointScanner.scan(project)
        }

        assertFalse(result.springDetected)
        assertTrue(result.endpoints.isEmpty())
    }

    fun testGeneratesEditableRequestFromMethod() {
        addSpringStubs()
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/PostMapping.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface PostMapping {
                String[] value() default {};
                String[] path() default {};
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/ValueConstants.java",
            """
            package org.springframework.web.bind.annotation;
            public interface ValueConstants {
                String DEFAULT_NONE = "\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n";
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestParam.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface RequestParam {
                String name() default "";
                String value() default "";
                String defaultValue() default "\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n";
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestBody.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface RequestBody {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "io/swagger/v3/oas/annotations/Operation.java",
            """
            package io.swagger.v3.oas.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface Operation {
                String summary() default "";
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "io/swagger/v3/oas/annotations/media/Schema.java",
            """
            package io.swagger.v3.oas.annotations.media;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
            public @interface Schema {
                String example() default "";
                String name() default "";
                String defaultValue() default "";
                Class<?> implementation() default Void.class;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/SearchController.java",
            """
            package com.example;

            import io.swagger.v3.oas.annotations.Operation;
            import io.swagger.v3.oas.annotations.media.Schema;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api")
            public class SearchController {
                @PostMapping("/search/{scope}")
                @Operation(summary = "搜索邀请")
                public String search(
                        @RequestParam("q") String q,
                        @RequestParam(defaultValue = "1") int page,
                        String keyword,
                        @RequestBody SearchRequest body) {
                    return "";
                }

                public static class SearchRequest {
                    @Schema(example = "邀请")
                    public String keyword;
                    public int page;
                    public boolean active;
                    public java.util.List<String> tags;
                    @Schema(name = "display_name", example = "Ada")
                    public String name;
                }
            }
            """.trimIndent()
        )

        val controller = JavaPsiFacade.getInstance(project)
            .findClass("com.example.SearchController", GlobalSearchScope.projectScope(project))
        assertNotNull(controller)
        val searchController = controller!!
        val method = searchController.findMethodsByName("search", false).first()
        // Precondition for the DEFAULT_NONE regression: PSI resolves the annotation's default value,
        // so a parameter without an explicit default appears to have this sentinel.
        val qAnnotation = method.parameterList.parameters
            .first { it.name == "q" }
            .annotations
            .first()
        val resolvedDefault = ReadAction.compute<String?, RuntimeException> {
            JavaPsiFacade.getInstance(project).constantEvaluationHelper
                .computeConstantExpression(qAnnotation.findAttributeValue("defaultValue")!!) as? String
        }
        assertTrue(
            "resolved default = ${resolvedDefault?.map { it.code }}",
            SpringRequestValues.isDefaultNone(resolvedDefault.orEmpty())
        )
        val payload = ReadAction.compute<SpringGenerationPayload?, RuntimeException> {
            SpringRequestFactory.prepareMethod(project, searchController, method)
        }
        assertNotNull(payload)
        val request = payload!!.requests.single()

        assertEquals("SearchController", payload.groupName)
        assertEquals("搜索邀请", request.name)
        assertEquals("POST", request.method)
        assertEquals("{{baseUrl}}/api/search/{scope}", request.url)
        assertEquals(listOf("scope"), request.pathParams.map { it.key })
        assertEquals(
            listOf("q" to "", "page" to "1", "keyword" to ""),
            request.params.map { it.key to it.value }
        )
        assertEquals(BodyType.JSON.name, request.bodyType)
        assertEquals(
            """
            {
              "keyword": "邀请",
              "page": 0,
              "active": false,
              "tags": [],
              "display_name": "Ada"
            }
            """.trimIndent(),
            request.body
        )
    }

    fun testGutterMarkersAreAvailableForControllerAndMethod() {
        addSpringStubs()
        myFixture.addFileToProject(
            "com/example/GutterController.java",
            """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class GutterController {
                @GetMapping("/hello")
                public String hello() {
                    return "";
                }
            }
            """.trimIndent()
        )

        val controller = JavaPsiFacade.getInstance(project)
            .findClass("com.example.GutterController", GlobalSearchScope.projectScope(project))
        assertNotNull(controller)
        val provider = SmartRequestLineMarkerProvider()

        val classMarker = provider.getLineMarkerInfo(controller!!.nameIdentifier!!)
        assertNotNull(classMarker)
        assertNotNull(classMarker!!.navigationHandler)
        assertEquals(
            "com.intellij.codeInsight.daemon.LineMarkerInfo\$LineMarkerGutterIconRenderer",
            classMarker.createGutterRenderer()::class.java.name
        )

        val method = controller.findMethodsByName("hello", false).first()
        val methodMarker = provider.getLineMarkerInfo(method.nameIdentifier!!)
        assertNotNull(methodMarker)
        assertNotNull(methodMarker!!.navigationHandler)
        assertEquals(
            "com.intellij.codeInsight.daemon.LineMarkerInfo\$LineMarkerGutterIconRenderer",
            methodMarker.createGutterRenderer()::class.java.name
        )
    }

    private fun addSpringStubs() {
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestMethod.java",
            """
            package org.springframework.web.bind.annotation;
            public enum RequestMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestMapping.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface RequestMapping {
                String[] value() default {};
                String[] path() default {};
                RequestMethod[] method() default {};
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/GetMapping.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface GetMapping {
                String[] value() default {};
                String[] path() default {};
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RestController.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface RestController {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "org/springframework/stereotype/Controller.java",
            """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Controller {}
            """.trimIndent()
        )
    }
}
