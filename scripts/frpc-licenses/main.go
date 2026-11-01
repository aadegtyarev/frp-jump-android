// Command frpc-licenses lists the Go modules built into frpc, with their
// licenses, for Settings › Components and licenses. scripts/build-frpc.sh
// runs it in the Go container after the build (or alone, on the binaries
// already in jniLibs, with -licenses-only):
//
//	go run main.go -frp-version 0.70.0 -frp-src /src -out /outlic libfrpc.so...
//
// The module list is the binaries' own build info (what `go version -m`
// prints); each module's license files are read from the module cache
// that `go mod download` filled (no network here), recognised by their
// wording, and copied into -out once per distinct text; a NOTICE file
// goes along as it is. -out gets modules.json and the texts, nothing else.
// Only the standard library is used, so `go run` needs nothing to fetch.
package main

import (
	"crypto/sha256"
	"debug/buildinfo"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
)

// List is modules.json.
type List struct {
	Frp     string   `json:"frp"`
	Go      string   `json:"go"`
	Modules []Module `json:"modules"`
}

// Module is one module built into frpc: its Path and Version as frp's
// go.mod requires it, and, when go.mod replaces it, the module whose code
// is in the binary (ReplacedBy); Sum is go.sum's hash of that code, Source
// where it is at that version.
type Module struct {
	Path       string     `json:"path"`
	Version    string     `json:"version"`
	ReplacedBy *ModuleRef `json:"replacedBy,omitempty"`
	Sum        string     `json:"sum,omitempty"`
	Source     string     `json:"source"`
	Licenses   []License  `json:"licenses"`
}

type ModuleRef struct {
	Path    string `json:"path"`
	Version string `json:"version"`
}

// License is one license file of a module: the licenses it holds (SPDX
// ids; NOTICE for a notice file), the File in the module, and Text, the
// asset with its text.
type License struct {
	IDs  []string `json:"ids"`
	File string   `json:"file"`
	Text string   `json:"text"`
}

func main() {
	frpVersion := flag.String("frp-version", "", "the frp version built (frp's own build info says (devel))")
	frpSrc := flag.String("frp-src", "", "frp's source tree, for its LICENSE")
	out := flag.String("out", "", "directory for modules.json and the texts (emptied first)")
	prefix := flag.String("asset-prefix", "licenses/frpc/", "the texts' path in the app's assets")
	flag.Parse()
	if *frpVersion == "" || *frpSrc == "" || *out == "" || flag.NArg() == 0 {
		fail("usage: frpc-licenses -frp-version V -frp-src DIR -out DIR binary...")
	}
	modCache := goEnv("GOMODCACHE", filepath.Join(os.Getenv("HOME"), "go", "pkg", "mod"))

	g := &generator{out: *out, prefix: *prefix, texts: map[string]string{}}
	// Emptied, not removed: the script mounts it into the container.
	if err := os.MkdirAll(*out, 0o755); err != nil {
		fail(err.Error())
	}
	old, err := os.ReadDir(*out)
	if err != nil {
		fail(err.Error())
	}
	for _, e := range old {
		if err := os.RemoveAll(filepath.Join(*out, e.Name())); err != nil {
			fail(err.Error())
		}
	}

	list := List{Frp: *frpVersion}
	seen := map[string]Module{}
	for _, bin := range flag.Args() {
		info, err := buildinfo.ReadFile(bin)
		if err != nil {
			fail(fmt.Sprintf("%s: %v", bin, err))
		}
		if info.Main.Path != "github.com/fatedier/frp" {
			fail(fmt.Sprintf("%s: built from %s, not frp", bin, info.Main.Path))
		}
		if list.Go != "" && list.Go != info.GoVersion {
			fail(fmt.Sprintf("%s: built with %s, the others with %s", bin, info.GoVersion, list.Go))
		}
		list.Go = info.GoVersion
		for _, dep := range info.Deps {
			m := Module{Path: dep.Path, Version: dep.Version, Sum: dep.Sum}
			code := ModuleRef{dep.Path, dep.Version}
			if dep.Replace != nil {
				code = ModuleRef{dep.Replace.Path, dep.Replace.Version}
				m.ReplacedBy = &code
				m.Sum = dep.Replace.Sum
			}
			if prev, ok := seen[m.Path]; ok {
				// The ABIs' binaries come from one go.mod: a module differs only if that broke.
				if prev.Version != m.Version || prev.Sum != m.Sum {
					fail(fmt.Sprintf("%s: %s %s here, %s in another binary", bin, m.Path, m.Version, prev.Version))
				}
				continue
			}
			dir := filepath.Join(modCache, escape(code.Path)+"@"+code.Version)
			m.Source = sourceURL(code.Path, code.Version)
			m.Licenses = g.licenses(dir)
			seen[m.Path] = m
		}
	}

	// frp itself and the Go standard library, at the top.
	frp := Module{Path: "github.com/fatedier/frp", Version: "v" + *frpVersion,
		Source: sourceURL("github.com/fatedier/frp", "v"+*frpVersion), Licenses: g.licenses(*frpSrc)}
	goRoot := goEnv("GOROOT", runtime.GOROOT())
	std := Module{Path: "std", Version: list.Go,
		Source: "https://go.googlesource.com/go/+/refs/tags/" + list.Go, Licenses: g.licensesIn(goRoot, []string{"LICENSE"})}
	list.Modules = []Module{frp, std}
	paths := make([]string, 0, len(seen))
	for p := range seen {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		list.Modules = append(list.Modules, seen[p])
	}

	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		fail(err.Error())
	}
	if err := os.WriteFile(filepath.Join(*out, "modules.json"), append(data, '\n'), 0o644); err != nil {
		fail(err.Error())
	}
	counts := map[string]int{}
	for _, m := range list.Modules {
		for _, id := range moduleIDs(m) {
			counts[id]++
		}
	}
	fmt.Printf("%d modules (frp %s, %s), %d texts; by license: %v\n", len(list.Modules), list.Frp, list.Go, len(g.texts), counts)
}

type generator struct {
	out, prefix string
	texts       map[string]string // a text's hash -> its asset
}

// licenses are the license files anywhere in the module at dir (forked
// code keeps its own: go-jose's json, k8s's forked/golang), test data
// aside; a module without one stops the run.
func (g *generator) licenses(dir string) []License {
	if _, err := os.Stat(dir); err != nil {
		fail(fmt.Sprintf("%s is not in the module cache: run go mod download in frp's tree first", dir))
	}
	var files []string
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if d.Name() == "testdata" || d.Name() == "vendor" {
				return filepath.SkipDir
			}
			return nil
		}
		rel, _ := filepath.Rel(dir, p)
		if isLicenseFile(rel) {
			files = append(files, rel)
		}
		return nil
	})
	if err != nil {
		fail(err.Error())
	}
	sort.Strings(files)
	return g.licensesIn(dir, files)
}

func (g *generator) licensesIn(dir string, files []string) []License {
	var out []License
	for _, f := range files {
		raw, err := os.ReadFile(filepath.Join(dir, f))
		if err != nil {
			fail(err.Error())
		}
		ids := []string{"NOTICE"}
		if !strings.HasPrefix(strings.ToUpper(path.Base(filepath.ToSlash(f))), "NOTICE") {
			ids = classify(string(raw))
			if len(ids) == 0 {
				fail(fmt.Sprintf("%s: a license this script does not recognise; add its wording to classify()", filepath.Join(dir, f)))
			}
		}
		out = append(out, License{IDs: ids, File: filepath.ToSlash(f), Text: g.text(ids, raw)})
	}
	if len(out) == 0 {
		fail(fmt.Sprintf("%s: no license file", dir))
	}
	return out
}

// text writes raw once per distinct text (Apache's, Go's BSD) and returns its asset.
func (g *generator) text(ids []string, raw []byte) string {
	norm := strings.Join(strings.Fields(string(raw)), " ")
	sum := sha256.Sum256([]byte(norm))
	hash := hex.EncodeToString(sum[:])[:10]
	if asset, ok := g.texts[hash]; ok {
		return asset
	}
	name := strings.Join(ids, "+") + "-" + hash + ".txt"
	if err := os.WriteFile(filepath.Join(g.out, name), raw, 0o644); err != nil {
		fail(err.Error())
	}
	g.texts[hash] = g.prefix + name
	return g.texts[hash]
}

var licenseName = regexp.MustCompile(`(?i)^(licen[cs]e|copying|notice)([.\-_].*)?$`)

// isLicenseFile: LICENSE, LICENSE.txt, license, COPYING, NOTICE, LICENSE.libyaml, and files in a LICENSES/ directory (REUSE).
func isLicenseFile(rel string) bool {
	rel = filepath.ToSlash(rel)
	base := path.Base(rel)
	if licenseName.MatchString(base) {
		return true
	}
	return strings.EqualFold(path.Base(path.Dir(rel)), "LICENSES")
}

// classify names the licenses in text by their wording; several for a
// file that holds more than one (sigs.k8s.io/yaml: MIT, then Go's BSD).
func classify(text string) []string {
	t := strings.ToLower(strings.Join(strings.Fields(text), " "))
	var ids []string
	if strings.Contains(t, "mozilla public license, version 2.0") || strings.Contains(t, "mozilla public license version 2.0") {
		ids = append(ids, "MPL-2.0")
	}
	if strings.Contains(t, "apache license") && strings.Contains(t, "version 2.0") {
		ids = append(ids, "Apache-2.0")
	}
	if strings.Contains(t, "permission is hereby granted, free of charge, to any person") {
		ids = append(ids, "MIT")
	}
	if strings.Contains(t, "permission to use, copy, modify, and/or distribute this software for any purpose") {
		ids = append(ids, "ISC")
	}
	// REUSE's LICENSES/ in pion: CC0 for some of its non-code files.
	if strings.Contains(t, "creative commons legal code cc0 1.0 universal") {
		ids = append(ids, "CC0-1.0")
	}
	if strings.Contains(t, "redistribution and use in source and binary forms") {
		if strings.Contains(t, "neither the name") || strings.Contains(t, "may not be used to endorse") {
			ids = append(ids, "BSD-3-Clause")
		} else {
			ids = append(ids, "BSD-2-Clause")
		}
	}
	return ids
}

func moduleIDs(m Module) []string {
	set := map[string]bool{}
	for _, l := range m.Licenses {
		for _, id := range l.IDs {
			set[id] = true
		}
	}
	var ids []string
	for id := range set {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

var pseudo = regexp.MustCompile(`-(?:\d+\.)?\d{14}-([0-9a-f]{12})$`)
var major = regexp.MustCompile(`^v[0-9]+$`)

// sourceURL is where the module's code is at version: the tag or commit
// on GitHub or go.googlesource.com, pkg.go.dev for anything else.
func sourceURL(modPath, version string) string {
	ref := strings.TrimSuffix(version, "+incompatible")
	if m := pseudo.FindStringSubmatch(ref); m != nil {
		ref = m[1]
	}
	parts := strings.Split(modPath, "/")
	switch {
	case parts[0] == "github.com" && len(parts) >= 3 && (len(parts) == 3 || len(parts) == 4 && major.MatchString(parts[3])):
		return "https://github.com/" + parts[1] + "/" + parts[2] + "/tree/" + ref
	case parts[0] == "golang.org" && len(parts) == 3 && parts[1] == "x":
		if ref == strings.TrimSuffix(version, "+incompatible") {
			ref = "refs/tags/" + ref
		}
		return "https://go.googlesource.com/" + parts[2] + "/+/" + ref
	}
	return "https://pkg.go.dev/" + modPath + "@" + version
}

// escape is the module cache's case encoding: "Azure" is "!azure".
func escape(p string) string {
	var b strings.Builder
	for _, r := range p {
		if r >= 'A' && r <= 'Z' {
			b.WriteByte('!')
			r += 'a' - 'A'
		}
		b.WriteRune(r)
	}
	return b.String()
}

func goEnv(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, "frpc-licenses:", msg)
	os.Exit(1)
}
