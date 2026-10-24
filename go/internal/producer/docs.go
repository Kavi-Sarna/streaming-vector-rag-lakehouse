package producer

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Doc is one markdown file from the seed corpus.
type Doc struct {
	ID       string
	Markdown string
	// Tenant is set when the file's front matter names one.
	Tenant string
}

// LoadDocs reads every .md file under dir, including subdirectories, in path order.
func LoadDocs(dir string) ([]Doc, error) {
	var paths []string
	err := filepath.WalkDir(dir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".md") {
			return nil
		}
		paths = append(paths, path)
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", dir, err)
	}
	sort.Strings(paths)
	if len(paths) == 0 {
		return nil, fmt.Errorf("no markdown files in %s", dir)
	}
	docs := make([]Doc, 0, len(paths))
	for _, path := range paths {
		body, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", path, err)
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return nil, fmt.Errorf("path %s: %w", path, err)
		}
		id := strings.TrimSuffix(filepath.ToSlash(rel), ".md")
		docs = append(docs, Doc{
			ID:       id,
			Markdown: string(body),
			Tenant:   frontMatterTenant(string(body)),
		})
	}
	return docs, nil
}

func frontMatterTenant(markdown string) string {
	scanner := bufio.NewScanner(strings.NewReader(markdown))
	if !scanner.Scan() || strings.TrimSpace(scanner.Text()) != "---" {
		return ""
	}
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "---" {
			return ""
		}
		key, value, ok := strings.Cut(line, ":")
		if !ok || strings.TrimSpace(key) != "tenant_id" {
			continue
		}
		return strings.Trim(strings.TrimSpace(value), `"'`)
	}
	return ""
}
