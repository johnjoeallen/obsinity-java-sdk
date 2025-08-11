#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   run-profiles-perl.sh -n /path/to/profiles.xml [-- mvn extra args]
#
# Behaviour:
#   - Uses ./pom.xml (current dir).
#   - Replaces its <profiles> with the one from the provided XML file.
#   - Activates profile = basename(profiles.xml) without extension.
#   - Runs: mvn -f <temp-pom> -P <basename> clean install

POM="./pom.xml"
PROFILES_XML=""
MVN_EXTRAS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) PROFILES_XML="$2"; shift 2 ;;
    --) shift; MVN_EXTRAS=("$@"); break ;;
    -h|--help)
      sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -f "$POM" ]] || { echo "Error: ./pom.xml not found"; exit 1; }
[[ -n "${PROFILES_XML:-}" && -f "$PROFILES_XML" ]] || { echo "Error: -n /path/to/profiles.xml is required"; exit 1; }
command -v mvn >/dev/null || { echo "Error: mvn not found in PATH"; exit 1; }
command -v perl >/dev/null || { echo "Error: perl not found in PATH"; exit 1; }

# Derive profile id from filename (minus last extension)
base="$(basename "$PROFILES_XML")"
PROFILE_ID="${base%.*}"

# Make a temp pom (portable mktemp)
tmpbase="$(mktemp -t pom.XXXXXX 2>/dev/null || mktemp)"
TMP_POM="${tmpbase%.xml}.xml"
cp -f "$POM" "$TMP_POM"
trap 'rm -f "$TMP_POM" || true' EXIT

# Perl: try robust XML replacement via XML::LibXML; if unavailable, fall back to a safe-ish regex splice.
perl - "$TMP_POM" "$PROFILES_XML" <<'PERL' || {
  echo "Perl edit failed." >&2; exit 2;
}
use strict;
use warnings;

my ($pom_path, $profiles_path) = @ARGV;

sub replace_via_libxml {
  eval {
    require XML::LibXML;
    XML::LibXML->import();
    my $pom_doc = XML::LibXML->load_xml(location => $pom_path);
    my $pom_root = $pom_doc->documentElement();
    my $pom_ns   = $pom_root->namespaceURI;

    my $prof_doc = XML::LibXML->load_xml(location => $profiles_path);
    my $prof_root = $prof_doc->documentElement();

    # Find <profiles> in the profiles file (root or descendant)
    my $profiles_node;
    if (($prof_root->localname||'') eq 'profiles') {
      $profiles_node = $prof_root;
    } else {
      ($profiles_node) = $prof_doc->findnodes('//*[local-name()="profiles"]');
    }
    die "profiles.xml has no <profiles>\n" unless $profiles_node;

    # Import node into POM doc and align namespaces to POM's default ns
    my $imported = $pom_doc->importNode($profiles_node, 1);

    my $set_ns;
    $set_ns = sub {
      my ($n) = @_;
      $n->setNamespace($pom_ns, undef, 1) if defined $pom_ns;
      for my $c ($n->nonBlankChildNodes()) { $set_ns->($c) if $c->nodeType == XML_ELEMENT_NODE }
    };
    $set_ns->($imported);

    # Replace existing <profiles> (direct child of <project>) or append
    my ($existing) = $pom_doc->findnodes('/*[local-name()="project"]/*[local-name()="profiles"]');
    if ($existing) {
      $existing->replaceNode($imported);
    } else {
      $pom_root->appendChild($imported);
    }

    $pom_doc->toFile($pom_path, 1);
    1;
  } or do {
    my $err = $@ || 'unknown error';
    die "XML::LibXML path failed: $err";
  };
}

sub replace_via_regex {
  # Slurp files
  local $/ = undef;
  open my $pf, '<', $profiles_path or die "open $profiles_path: $!";
  my $ptext = <$pf>; close $pf;
  $ptext =~ s/^\s*<\?xml[^>]*\?>\s*//s; # strip XML decl if present

  # Extract inner content of <profiles>
  my ($inner) = $ptext =~ m{<profiles\b[^>]*>(.*)</profiles>}si;
  die "profiles.xml missing <profiles> content\n" unless defined $inner;

  open my $pomf, '<', $pom_path or die "open $pom_path: $!";
  my $pom = <$pomf>; close $pomf;

  # Replace the first <profiles>...</profiles> block; if none, append before </project>
  if ($pom =~ s{<profiles\b[^>]*>.*?</profiles>}{"<profiles>$inner</profiles>"}si) {
    # replaced in-place
  } else {
    $pom =~ s{</project>}{"  <profiles>$inner</profiles>\n</project>"}si
      or die "Could not find </project> to append profiles\n";
  }

  open my $out, '>', $pom_path or die "write $pom_path: $!";
  print {$out} $pom; close $out;
  1;
}

# Try robust path first; fall back to regex
eval { replace_via_libxml(); 1 } or do {
  warn "[warn] XML::LibXML not available or failed; falling back to regex splice\n";
  replace_via_regex() or die "regex splice failed\n";
};

PERL

echo "[maven] Using profile: $PROFILE_ID"
echo "[maven] mvn -f \"$TMP_POM\" -P \"$PROFILE_ID\" clean install ${MVN_EXTRAS[*]}"
mvn -f "$TMP_POM" -P "$PROFILE_ID" clean install "${MVN_EXTRAS[@]}"
