import { describe, expect, it } from "vitest";
import { singleParams, validId, validSteamIds, validTerm, validTitle } from "../src/validate.js";

describe("validTerm", () => {
  it("trims and accepts ordinary names, including non-ASCII ones", () => {
    expect(validTerm("  The Witcher 3: Wild Hunt ")).toBe("The Witcher 3: Wild Hunt");
    expect(validTerm("Pok\u00e9mon Legends: Arceus")).toBe("Pok\u00e9mon Legends: Arceus");
    expect(validTerm("\u30c9\u30e9\u30b4\u30f3\u30af\u30a8\u30b9\u30c8")).not.toBeNull();
    expect(validTerm('Say "Hi" \\ [x] {y} #1')).toBe('Say "Hi" \\ [x] {y} #1');
  });

  it("rejects empty, blank, oversize and control-character input", () => {
    expect(validTerm("")).toBeNull();
    expect(validTerm("   ")).toBeNull();
    expect(validTerm("a".repeat(129))).toBeNull();
    expect(validTerm("a".repeat(128))).not.toBeNull();
    expect(validTerm("two\nlines")).toBeNull();
    expect(validTerm("tab\there")).toBeNull();
    expect(validTerm("nul\u0000")).toBeNull();
    expect(validTerm("c1\u0085")).toBeNull();
    expect(validTerm("se\u2028p")).toBeNull();
    expect(validTerm(null)).toBeNull();
    expect(validTerm(5)).toBeNull();
  });

  it("counts code points, not UTF-16 units", () => {
    expect(validTerm("\u{1F3AE}".repeat(128))).not.toBeNull();
    expect(validTerm("\u{1F3AE}".repeat(129))).toBeNull();
  });
});

describe("validTitle", () => {
  it("accepts wiki-legal titles and rejects what no MediaWiki title can contain", () => {
    expect(validTitle("Sea of Stars: Sunset Edition")).toBe("Sea of Stars: Sunset Edition");
    expect(validTitle("Fez (2012)")).toBe("Fez (2012)");
    for (const bad of ["a#b", "a<b", "a>b", "a[b", "a]b", "a{b", "a}b", "a|b"]) expect(validTitle(bad)).toBeNull();
    expect(validTitle("a".repeat(201))).toBeNull();
    expect(validTitle("a".repeat(200))).not.toBeNull();
    expect(validTitle("line\nbreak")).toBeNull();
    expect(validTitle("")).toBeNull();
  });
});

describe("validId", () => {
  it("accepts canonical positive integers within range only", () => {
    expect(validId("1942", 2_000_000_000)).toBe(1942);
    for (const bad of ["0", "-1", "01", "1.5", "1e3", "abc", "", " 1", "1 ", "+1", "99999999999", "2000000001", "\u0661"]) {
      expect(validId(bad, 2_000_000_000)).toBeNull();
    }
    expect(validId(7, 10)).toBeNull();
  });
});

describe("validSteamIds", () => {
  it("returns distinct ascending ids", () => {
    expect(validSteamIds("20,10,10,3")).toEqual([3, 10, 20]);
    expect(validSteamIds("4294967295")).toEqual([4294967295]);
  });

  it("rejects malformed lists, oversize ids and more than 20 ids", () => {
    for (const bad of ["", ",", "1,", ",1", "1,,2", "0", "-1", "1 ,2", "a", "4294967296", "1;2"]) expect(validSteamIds(bad)).toBeNull();
    const many = (n) => Array.from({ length: n }, (_, i) => i + 1).join(",");
    expect(validSteamIds(many(20))).toHaveLength(20);
    expect(validSteamIds(many(21))).toBeNull();
  });
});

describe("singleParams", () => {
  const parse = (query, allowed, options) => singleParams(new URLSearchParams(query), allowed, options);
  it("allows only listed names, each once", () => {
    expect(parse("a=1", ["a", "b"])).toEqual({ a: "1" });
    expect(parse("a=1&c=2", ["a", "b"])).toBeNull();
    expect(parse("a=1&a=2", ["a"])).toBeNull();
    expect(parse("", ["a"], { required: ["a"] })).toBeNull();
    expect(parse("", ["a"])).toEqual({});
  });
});
