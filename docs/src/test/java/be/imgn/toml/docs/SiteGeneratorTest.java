package be.imgn.toml.docs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteGeneratorTest {

    @TempDir
    Path directory;

    private Path markdown;
    private Path theme;
    private Path output;

    @BeforeEach
    void createTheme() throws IOException {
        markdown = Files.createDirectories(directory.resolve("markdown"));
        theme = Files.createDirectories(directory.resolve("theme"));
        output = directory.resolve("output");
        write(theme.resolve("template.html"), "<title>{{title}}</title>\n<nav>{{navigation}}</nav>\n{{content}}");
        write(theme.resolve("style.css"), "body {}");
    }

    @Test
    void writesEachPageInTheTemplate() throws IOException {
        page("index.md", "# toml\n\nA TOML library.");
        page("binding.md", "# Records & enums\n\nBinding.");
        pages("index.md", "binding.md");

        SiteGenerator.generate(markdown, theme, output);

        assertThat(output.resolve("index.html"))
                .content(UTF_8)
                .contains("<title>toml</title>")
                .contains("<li><a href=\"index.html\" aria-current=\"page\">toml</a></li>\n"
                        + "<li><a href=\"binding.html\">Records &amp; enums</a></li>")
                .contains("<p>A TOML library.</p>");
        assertThat(output.resolve("binding.html"))
                .content(UTF_8)
                .contains("<title>Records &amp; enums · toml</title>")
                .contains("<li><a href=\"binding.html\" aria-current=\"page\">");
        assertThat(output.resolve("style.css")).hasContent("body {}");
        assertThat(output.resolve("template.html")).doesNotExist();
    }

    @Test
    void identifiesHeadingsAsGitHubDoes() throws IOException {
        page("index.md", "# toml\n\n## Parsing `text`, fast!\n\n## Parsing `text`, fast!\n\n### Über_alles\n");
        pages("index.md");

        SiteGenerator.generate(markdown, theme, output);

        assertThat(output.resolve("index.html"))
                .content(UTF_8)
                .contains("<h1 id=\"toml\">toml</h1>")
                .contains("<h2 id=\"parsing-text-fast\">")
                .contains("<h2 id=\"parsing-text-fast-1\">")
                .contains("<h3 id=\"über_alles\">");
    }

    @Test
    void resolvesLinks() throws IOException {
        page("index.md", """
                # toml

                [Binding](binding.md), [enums](binding.md#enums), [above](#toml), \
                [web](https://example.com/a.md), [TomlTable](api:be.imgn.toml.TomlTable), \
                [Builder](api:be.imgn.toml.TomlTable.Builder), \
                [bundles](api:be.imgn.toml.i18n.TomlResourceBundle)
                """);
        page("binding.md", "# Binding\n\n## Enums\n");
        pages("index.md", "binding.md");

        SiteGenerator.generate(markdown, theme, output);

        assertThat(output.resolve("index.html"))
                .content(UTF_8)
                .contains("<a href=\"binding.html\">Binding</a>")
                .contains("<a href=\"binding.html#enums\">enums</a>")
                .contains("<a href=\"#toml\">above</a>")
                .contains("<a href=\"https://example.com/a.md\">web</a>")
                .contains("<a href=\"api/latest/be.imgn.toml/be/imgn/toml/TomlTable.html\">TomlTable</a>")
                .contains("<a href=\"api/latest/be.imgn.toml/be/imgn/toml/TomlTable.Builder.html\">Builder</a>")
                .contains("<a href=\"api/latest/be.imgn.toml/be/imgn/toml/i18n/TomlResourceBundle.html\">");
    }

    @Test
    void reportsEveryProblem() throws IOException {
        page("index.md", """
                # toml

                [a](missing.md) [b](binding.md#nowhere) [c](#nowhere) [d](other.txt)
                [e](api:be.imgn.toml.Nothing) [f](api:java.lang.String)
                [g](api:be.imgn.toml.Parser) [h](api:be.imgn.gaia.core.text.Splitter)
                ![i](picture.png)
                """);
        page("binding.md", "No title.");
        page("unlisted.md", "# Unlisted");
        pages("index.md", "binding.md", "binding.md", "absent.md", "notes.txt");

        assertThatIllegalStateException()
                .isThrownBy(() -> SiteGenerator.generate(markdown, theme, output))
                .withMessage("""
                        The guide has problems:
                          binding.md: no level-1 heading for the title
                          pages.txt: binding.md is listed twice
                          pages.txt: no page absent.md
                          pages.txt: no page notes.txt
                          unlisted.md: not listed in pages.txt
                          index.md: broken link to missing.md
                          index.md: no heading nowhere in binding.md
                          index.md: no heading nowhere in index.md
                          index.md: broken link to other.txt
                          index.md: no API documentation for be.imgn.toml.Nothing
                          index.md: no API documentation for java.lang.String
                          index.md: no API documentation for be.imgn.toml.Parser
                          index.md: no API documentation for be.imgn.gaia.core.text.Splitter
                          index.md: images are not supported: picture.png""");
        assertThat(output).doesNotExist();
    }

    @Test
    void reportsRawHtml() throws IOException {
        page("index.md", """
                # toml

                <div class="note">
                Raw.
                </div>

                Some <b>bold</b> text.

                ```html
                <div>In a code block.</div>
                ```

                In code: `<b>`.
                """);
        pages("index.md");

        assertThatIllegalStateException()
                .isThrownBy(() -> SiteGenerator.generate(markdown, theme, output))
                .withMessage("""
                        The guide has problems:
                          index.md: HTML is not supported: <div class="note">
                          index.md: HTML is not supported: <b>
                          index.md: HTML is not supported: </b>""");
    }

    @Test
    void copiesTheThemeWithItsDirectories() throws IOException {
        write(Files.createDirectories(theme.resolve("fonts/latin")).resolve("text.woff2"), "font");
        page("index.md", "# toml");
        pages("index.md");

        SiteGenerator.generate(markdown, theme, output);

        assertThat(output.resolve("fonts/latin/text.woff2")).hasContent("font");
        assertThat(output.resolve("style.css")).hasContent("body {}");
    }

    @Test
    void requiresAHomePage() throws IOException {
        page("binding.md", "# Binding");
        pages("binding.md");

        assertThatIllegalStateException()
                .isThrownBy(() -> SiteGenerator.generate(markdown, theme, output))
                .withMessage("The guide has problems:\n  pages.txt: no index.md");
    }

    private void page(String name, String content) throws IOException {
        write(markdown.resolve(name), content);
    }

    private void pages(String... names) throws IOException {
        write(markdown.resolve("pages.txt"), "# The pages, in order\n\n" + String.join("\n", names) + "\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, UTF_8);
    }
}
