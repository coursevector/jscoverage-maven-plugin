package com.github.timurstrekalov

import java.io.File
import java.util.List

import org.codehaus.groovy.maven.mojo.GroovyMojo

import com.gargoylesoftware.htmlunit.BrowserVersion
import com.gargoylesoftware.htmlunit.IncorrectnessListener
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController
import com.gargoylesoftware.htmlunit.ScriptPreProcessor
import com.gargoylesoftware.htmlunit.WebClient
import com.gargoylesoftware.htmlunit.html.HtmlElement
import com.gargoylesoftware.htmlunit.html.HtmlPage
import com.github.timurstrekalov.coverage.Coverage
import com.github.timurstrekalov.reporter.ConsoleReporter
import com.github.timurstrekalov.reporter.CsvReporter
import com.github.timurstrekalov.reporter.XmlReporter

/**
* Generates coverage and outputs it to the log.
*
* @goal coverage
* @phase test
*
* @author Timur Strekalov
*/
class GenerateCoverageMojo extends GroovyMojo {

    private final String returnJsCoverageVarScript = 'window._$jscoverage.toSource()'
    private final String coverageScript = getClass().getResourceAsStream("/jscoverage.js").text

    /**
     * @parameter
     */
    File instrumentedSrcDir

    /**
     * @parameter
     */
    List<String> tests

    /**
     * A list of formats to generate. Supported values are: csv, xml, console
     *
     * @parameter
     */
    List<String> formats

    /**
     * CSV report delimiter. Will be ignored if CSV is not selected as one of
     * the report formats.
     *
     * @parameter default-value=";"
     */
    String csvDelimiter

    /**
     * @parameter default-value="${project.build.directory}"
     */
    String testBaseDir

    /**
     * Directory where the coverage files will be written
     *
     * @parameter default-value="${project.build.directory}${file.separator}coverage"
     */
    String coverageOutputDir

    public void execute() {
        def webClient = new WebClient(BrowserVersion.FIREFOX_3_6)

        webClient.javaScriptEnabled = true
        webClient.ajaxController = new NicelyResynchronizingAjaxController()
        webClient.incorrectnessListener = { message, origin -> } as IncorrectnessListener

        def preProcessor = new PreProcessor(instrumentedSrcDir)

        webClient.scriptPreProcessor = preProcessor

        def scanner = ant.fileScanner {
            fileset(dir: testBaseDir, defaultexcludes: false) {
                tests.each {
                    include name: it
                }
            }
        }

        if (!scanner.iterator().hasNext()) {
            log.warn "No tests found"
            return
        }

        HtmlPage page

        log.info "Running tests"

        scanner.each {
            log.info "Running $it"

            page = webClient.getPage(it.toURI() as String)

            webClient.waitForBackgroundJavaScript 30000

            preProcessor.update(page.executeJavaScript(
                returnJsCoverageVarScript).javaScriptResult)
        }

        def jsResult = page.executeJavaScript(coverageScript).javaScriptResult

        webClient.closeAllWindows()

        generateReports(new Coverage(jsResult))
    }

    private void generateReports(coverage) {
        def out = new File(coverageOutputDir)
        if (!out.exists()) {
            out.mkdirs()
        }

        if ('csv' in formats) {
            generateCsvReport(coverage)
        }

        if ('xml' in formats) {
            generateXmlReport(coverage)
        }

        if ('console' in formats) {
            generateConsoleReport(coverage)
        }
    }

    private void generateCsvReport(Coverage coverage) {
        log.info "Generating CSV report"
        new CsvReporter("${coverageOutputDir}/coverage.csv", coverage,
            csvDelimiter).generate()
    }

    private void generateXmlReport(coverage) {
        log.info "Generating XML report"
        new XmlReporter("${coverageOutputDir}/coverage.xml", coverage).generate()
    }

    private void generateConsoleReport(Coverage coverage) {
        log.info "Generating console report"
        new ConsoleReporter(coverage).generate()
    }

}

class PreProcessor implements ScriptPreProcessor {

    def result = "{}"
    Boolean injected = false
    String instrumentedSrcDir

    PreProcessor(File instrumentedSrcDir) {
        this.instrumentedSrcDir = instrumentedSrcDir.toURI() as String
    }

    String preProcess(HtmlPage htmlPage, String sourceCode, String sourceName,
        int lineNumber, HtmlElement htmlElement) {

        if (!injected) {
            injected = true
            return "window._\$jscoverage = $result;\n$sourceCode"
        }

        if (sourceCode.indexOf("jasmine.plugin.jsSrcDir = ") != -1) {
            return sourceCode.replaceAll(
				/jasmine\.plugin\.jsSrcDir = '(.*)';/,
				"jasmine.plugin.jsSrcDir = '$instrumentedSrcDir';")
        }

        return sourceCode
    }

    void update(result) {
        this.result = result
        this.injected = false
    }

}
