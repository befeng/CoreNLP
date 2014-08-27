package edu.stanford.nlp.international.spanish.pipeline;

import java.util.ArrayList;
import java.util.List;

import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeFactory;
import edu.stanford.nlp.trees.TreeNormalizer;
import edu.stanford.nlp.trees.international.spanish.SpanishTreeNormalizer;
import edu.stanford.nlp.trees.tregex.TregexMatcher;
import edu.stanford.nlp.trees.tregex.TregexPattern;
import edu.stanford.nlp.trees.tregex.tsurgeon.Tsurgeon;
import edu.stanford.nlp.trees.tregex.tsurgeon.TsurgeonPattern;
import edu.stanford.nlp.util.Pair;

/**
 * Provides routines for "decompressing" further the expanded trees
 * formed by multiword token splitting.
 *
 * Multiword token expansion leaves constituent words as siblings in a
 * "flat" tree structure. This often represents an incorrect parse of
 * the sentence. For example, the phrase "Ministerio de Finanzas" should
 * not be parsed as a flat structure like
 *
 *     (grup.nom (np00000 Ministerio) (sp000 de) (np00000 Finanzas))
 *
 * but rather a "deep" structure like
 *
 *     (grup.nom (sp (prep (sp000 de))
 *                   (sn (grup.nom (np0000 Finanzas)))))
 *
 * This class provides methods for detecting common linguistic patterns
 * that should be expanded in this way.
 */
public class MultiWordTreeExpander {

  /**
   * Regular expression to match groups inside which we want to expand things
   */
  private static final String CANDIDATE_GROUPS = "(^grup\\.(adv|c[cs]|[iwz]|nom|prep|pron|verb)|\\.inter)";

  private static final String PREPOSITIONS =
    "(por|para|al?|del?|con(?:tra)?|sobre|en(?:tre)?|hacia|sin|según|hasta)";

  /**
   * Yes, some multiword tokens contain multiple clauses..
   */
  private static TregexPattern multipleClauses
    = TregexPattern.compile(
      // Nested nominal group containing period punctuation
      "/^grup\\.nom/ > /^grup\\.nom/ < (fp !$-- fp $- /^[^g]/=right1 $+ __=left2)" +
      // Match boundaries for subtrees created
      " <, __=left1 <` __=right2");

  private static TsurgeonPattern expandMultipleClauses
    = Tsurgeon.parseOperation("[createSubtree grup.nom left1 right1]" +
      "[createSubtree grup.nom left2 right2]");

  private static TregexPattern prepositionalPhrase
    = TregexPattern.compile(// Match candidate preposition
                            "sp000=tag < /(?i)^" + PREPOSITIONS + "$/" +
                            // Headed by a group that was generated from
                            // multi-word token expansion and that we
                            // wish to expand further
                            " > (/" + CANDIDATE_GROUPS + "/ <- __=right)" +
                            // With an NP on the left (-> this is a
                            // prep. phrase) and not preceded by any
                            // other prepositions
                            " $+ /^[adnswz]/=left !$-- sp000");

  private static TregexPattern leadingPrepositionalPhrase
    = TregexPattern.compile(// Match candidate preposition
                            "sp000=tag < /(?i)^" + PREPOSITIONS + "$/" +
                            // Which is the first child in a group that
                            // was generated from multi-word token
                            // expansion and that we wish to expand
                            // further
                            " >, (/" + CANDIDATE_GROUPS + "/ <- __=right)" +
                            // With an NP on the left (-> this is a
                            // prep. phrase) and not preceded by any
                            // other prepositions
                            " $+ /^[adnswz]/=left !$-- sp000");

  /**
   * First step in expanding prepositional phrases: group NP to right of
   * preposition under a `grup.nom` subtree (specially labeled for now
   * so that we can target it in the next step)
   */
  private static TsurgeonPattern expandPrepositionalPhrase1 =
    Tsurgeon.parseOperation("[createSubtree grup.nom.inter left right]");

  /**
   * Matches intermediate prepositional phrase structures as produced by
   * the first step of expansion.
   */
  private static TregexPattern intermediatePrepositionalPhrase
    = TregexPattern.compile("sp000=preptag $+ /^grup\\.nom\\.inter$/=gn");

  /**
   * Second step: replace intermediate prepositional phrase structure
   * with final result.
   */
  private static TsurgeonPattern expandPrepositionalPhrase2 =
    Tsurgeon.parseOperation("[adjoinF (sp (prep T=preptarget) (sn foot@)) gn]" +
                            "[relabel gn /.inter$//]" +
                            "[replace preptarget preptag]" +
                            "[delete preptag]");

  private static TregexPattern prepositionalVP =
    TregexPattern.compile("sp000=tag < /^(para|al?|del?)$/" +
                          " > (/" + CANDIDATE_GROUPS + "/ <- __=right)" +
                          " $+ vmn0000=left !$-- sp000");

  private static TsurgeonPattern expandPrepositionalVP1 =
    Tsurgeon.parseOperation("[createSubtree S.inter left right]" +
                            "[adjoinF (infinitiu foot@) left]");

  private static TregexPattern intermediatePrepositionalVP =
    TregexPattern.compile("sp000=preptag $+ /^S\\.inter$/=si");

  private static TsurgeonPattern expandPrepositionalVP2 =
    Tsurgeon.parseOperation("[adjoin (sp prep=target S@) si] [move preptag >0 target]");

  private static TregexPattern conjunctPhrase =
    TregexPattern.compile("cc=cc" +
                          // In one of our expanded phrases (match
                          // bounds of this expanded phrase; these form
                          // the left edge of first new subtree and the
                          // right edge of the second new subtree)
                          " > (/^grup\\.nom/ <, __=left1 <` __=right2)" +
                          // Fetch more bounds: node to immediate left
                          // of cc is the right edge of the first new
                          // subtree, and node to right of cc is the
                          // left edge of the second new subtree
                          //
                          // NB: left1 may the same as right1; likewise
                          // for the second tree
                          " $- /^[^g]/=right1 $+ /^[^g]/=left2");

  private static TsurgeonPattern expandConjunctPhrase =
    Tsurgeon.parseOperation("[adjoinF (conj foot@) cc]" +
                            "[createSubtree grup.nom.inter2 left1 right1]" +
                            "[createSubtree grup.nom.inter2 left2 right2]");

  /**
   * Simple intermediate conjunct: a constituent which heads a single
   * substantive
   */
  private static TregexPattern intermediateSubstantiveConjunct =
    TregexPattern.compile("/grup\\.nom\\.inter2/=target <: /^[dnpw]/");

  /**
   * Rename simple intermediate conjunct as a `grup.nom`
   */
  private static TsurgeonPattern expandIntermediateSubstantiveConjunct =
    Tsurgeon.parseOperation("[relabel target /grup.nom/]");

  /**
   * Simple intermediate conjunct: a constituent which heads a single
   * adjective
   */
  private static TregexPattern intermediateAdjectiveConjunct =
    TregexPattern.compile("/^grup\\.nom\\.inter2$/=target <: /^a/");

  /**
   * Rename simple intermediate adjective conjunct as a `grup.a`
   */
  private static TsurgeonPattern expandIntermediateAdjectiveConjunct =
    Tsurgeon.parseOperation("[relabel target /grup.a/]");

  /**
   * Match parts of an expanded conjunct which must be labeled as a noun
   * phrase given their children.
   */
  private static TregexPattern intermediateNounPhraseConjunct =
    TregexPattern.compile("/^grup\\.nom\\.inter2$/=target < /^s[pn]$/");

  private static TsurgeonPattern expandIntermediateNounPhraseConjunct =
    Tsurgeon.parseOperation("[relabel target sn]");

  /**
   * Match parts of an expanded conjunct which should be labeled as
   * nominal groups.
   */
  private static TregexPattern intermediateNominalGroupConjunct =
    TregexPattern.compile("/^grup\\.nom\\.inter2$/=target !< /^[^n]/");

  private static TsurgeonPattern expandIntermediateNominalGroupConjunct =
    Tsurgeon.parseOperation("[relabel target /grup.nom/]");

  /**
   * Match articles contained within nominal groups of substantives so
   * that they can be moved out
   */
  private static TregexPattern articleLeadingNominalGroup =
    TregexPattern.compile("/^d[ai]/=art >, (/^grup\\.nom$/=ng > sn)");

  private static TsurgeonPattern expandArticleLeadingNominalGroup =
    Tsurgeon.parseOperation("[insert (spec=target) $+ ng] [move art >0 target]");

  private static TregexPattern articleInsideOrphanedNominalGroup =
    TregexPattern.compile("/^d[ai]/=d >, (/^grup\\.nom/=ng !> sn)");

  private static TsurgeonPattern expandArticleInsideOrphanedNominalGroup =
    Tsurgeon.parseOperation("[adjoinF (sn=sn spec=spec foot@) ng] [move d >0 spec]");

  private static TregexPattern determinerInsideNominalGroup =
    TregexPattern.compile("/^d[^n]/=det >, (/^grup\\.nom/=ng > sn) $ __");

  private static TsurgeonPattern expandDeterminerInsideNominalGroup =
    Tsurgeon.parseOperation("[insert (spec=target) $+ ng] [move det >0 target]");

  // "en opinion del X," "además del Y"
  private static TregexPattern contractionTrailingIdiomBeforeNominalGroup
    = TregexPattern.compile("sp000 >` (/^grup\\.prep$/ > (__ $+ /^grup\\.nom/=ng)) < /^(de|a)l$/=contraction");

  // -> "(en opinion de) (el X)," "(además de) (el Y)"
  private static TsurgeonPattern joinArticleWithNominalGroup
    = Tsurgeon.parseOperation("[relabel contraction /l//] [adjoinF (sn (spec (da0000 el)) foot@) ng]");

  private static TregexPattern contractionInSpecifier
    = TregexPattern.compile("sp000=parent < /^(a|de)l$/=contraction > spec");

  private static TregexPattern delTodo = TregexPattern.compile("del=contraction . todo > sp000=parent");

  // "del X al Y"
  private static TregexPattern contractionInRangePhrase
    = TregexPattern.compile("sp000 < del=contraction >: (conj $+ (/^grup\\.(w|nom)/=group))");

  private static TsurgeonPattern expandContractionInRangePhrase
    = Tsurgeon.parseOperation("[relabel contraction de] [adjoinF (sn (spec (da0000 el)) foot@) group]");

  /**
   * Operation to extract article from contraction and just put it next to the container
   */
  private static TsurgeonPattern extendContraction
    = Tsurgeon.parseOperation("[relabel contraction /l//] [insert (da0000 el) $- parent]");

  // TODO intermediate adjectival conjunct
  // TODO intermediate verb conjunct

  // TODO date phrases

  // ---------

  // Final cleanup operations

  private static TregexPattern terminalPrepositions = TregexPattern.compile("sp000=sp < de >- (/^grup\\.nom/ > sn=sn)");
  private static TsurgeonPattern extractTerminalPrepositions = Tsurgeon.parseOperation(
    "[insert (prep=prep) $- sn] [move sp >0 prep]");

  private static TregexPattern adverbNominalGroups = TregexPattern.compile("/^grup\\.nom./=ng <: /^r[gn]/=r");
  private static TsurgeonPattern replaceAdverbNominalGroup = Tsurgeon.parseOperation("replace ng r");

  /**
   * Match `sn` constituents which can (should) be rewritten as nominal groups
   */
  private static TregexPattern nominalGroupSubstantives =
    TregexPattern.compile("sn=target < /^[adnwz]/ !< /^([^adnswz]|neg)/");

  private static TregexPattern leftoverIntermediates =
    TregexPattern.compile("/^grup\\.nom\\.inter/=target");

  private static TsurgeonPattern makeNominalGroup =
    Tsurgeon.parseOperation("[relabel target /grup.nom/]");

  private static TregexPattern redundantNominalRewrite =
    TregexPattern.compile("/^grup\\.nom$/ <: sn=child >: sn=parent");

  private static TsurgeonPattern fixRedundantNominalRewrite =
    Tsurgeon.parseOperation("[replace parent child]");

  /**
   * Expands flat structures into intermediate forms which will
   * eventually become deep phrase structures.
   */
  @SuppressWarnings("unchecked")
  private static List<Pair<TregexPattern, TsurgeonPattern>> firstStepExpansions =
    new ArrayList<Pair<TregexPattern, TsurgeonPattern>>() {{
      // Should be first-ish
      add(new Pair(multipleClauses, expandMultipleClauses));

      add(new Pair(leadingPrepositionalPhrase, expandPrepositionalPhrase1));
      add(new Pair(conjunctPhrase, expandConjunctPhrase));
      add(new Pair(prepositionalPhrase, expandPrepositionalPhrase1));
      add(new Pair(prepositionalVP, expandPrepositionalVP1));

      add(new Pair(contractionTrailingIdiomBeforeNominalGroup, joinArticleWithNominalGroup));
      add(new Pair(contractionInSpecifier, extendContraction));
      add(new Pair(delTodo, extendContraction));
      add(new Pair(contractionInRangePhrase, expandContractionInRangePhrase));

      // Should not happen until the last moment! The function words
      // being targeted have weaker "scope" than others earlier
      // targeted, and so we don't want to clump things around them
      // until we know we have the right to clump
      add(new Pair(articleLeadingNominalGroup, expandArticleLeadingNominalGroup));
      add(new Pair(articleInsideOrphanedNominalGroup, expandArticleInsideOrphanedNominalGroup));
      add(new Pair(determinerInsideNominalGroup, expandDeterminerInsideNominalGroup));
    }};

  /**
   * Clean up "intermediate" phrase structures produced by previous step
   * and produce something from them that looks like the rest of the
   * corpus.
   */
  @SuppressWarnings("unchecked")
  private static List<Pair<TregexPattern, TsurgeonPattern>> intermediateExpansions =
    new ArrayList<Pair<TregexPattern, TsurgeonPattern>>() {{
      add(new Pair(intermediatePrepositionalPhrase, expandPrepositionalPhrase2));
      add(new Pair(intermediatePrepositionalVP, expandPrepositionalVP2));

      add(new Pair(intermediateSubstantiveConjunct, expandIntermediateSubstantiveConjunct));
      add(new Pair(intermediateAdjectiveConjunct, expandIntermediateAdjectiveConjunct));
      add(new Pair(intermediateNounPhraseConjunct, expandIntermediateNounPhraseConjunct));
      add(new Pair(intermediateNominalGroupConjunct, expandIntermediateNominalGroupConjunct));
    }};

  /**
   * Last-minute cleanup of leftover grammar mistakes
   */
  @SuppressWarnings("unchecked")
  private static List<Pair<TregexPattern, TsurgeonPattern>> finalCleanup =
    new ArrayList<Pair<TregexPattern, TsurgeonPattern>>() {{
      add(new Pair(terminalPrepositions, extractTerminalPrepositions));
      add(new Pair(nominalGroupSubstantives, makeNominalGroup));
      add(new Pair(adverbNominalGroups, replaceAdverbNominalGroup));

      // Lastly..
      //
      // These final fixes are not at all linguistically motivated -- just need to make the trees less dirty
      add(new Pair(redundantNominalRewrite, fixRedundantNominalRewrite));
      //add(new Pair(leftoverIntermediates, makeNominalGroup));
    }};

  /**
   * Recognize candidate patterns for expansion in the given tree and
   * perform the expansions. See the class documentation for more
   * information.
   */
  public static Tree expandPhrases(Tree t, TreeNormalizer tn, TreeFactory tf) {
    // Keep running this sequence of patterns until no changes are
    // affected. We need this for nested expressions like "para tratar
    // de regresar al empleo." This first step produces lots of
    // "intermediate" tree structures which need to be cleaned up later.
    Tree oldTree;
    do {
      oldTree = t.deepCopy();
      t = Tsurgeon.processPatternsOnTree(firstStepExpansions, t);
    } while (!t.equals(oldTree));

    // Now clean up intermediate tree structures
    t = Tsurgeon.processPatternsOnTree(intermediateExpansions, t);

    // Normalize first to allow for contraction expansion, etc.
    t = tn.normalizeWholeTree(t, tf);

    // Final cleanup
    t = Tsurgeon.processPatternsOnTree(finalCleanup, t);

    return t;
  }

}

// Contrato . ayuda
// incidentes . lamentables (nested articles near middle)
// chiquilla . vistosa (giant multiword at end)
// espejo . deformante (article fun at start)
// menor . coste (watch "Comisión del Mercado" thing at end)
// triunfo . sitúa (periods in names at end)
// Diez . Minutos (new rule for terminal prepositions?)
// Abogados . y (parenthetical should be separated into its own clause)
// totalmente . evitables ("en opinion del" at end)
// hábitat . tradicional ("en cuerpo y alma" phrase)
// Eliécer . Hurtado ("salir al paso")
// /300/ . dólares ("en base a")
// otro . vicepresidente ("está al caer")
// o . coacción ("a favor del PRI")
// ¿ . (no . estabais) (weird grup.nom leaf?? mistagging of "contra" here leads to string of three prepositions, which messes up our heuristics)
// Nílton . Petrone ("en compañía del abogado y el fisioterapeuta..")
// Ernesto . Zedillo ("a partir del 1 de diciembre próximo")
// yo . (no . volvería) ("por nada del mundo")
// harakiri . a ("en vez del": prepositional phrase  functioning as conjunction)
// teatral . catalán (range phrase)
// Wiranto . ha ("al frente de")