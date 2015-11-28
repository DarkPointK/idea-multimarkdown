/*
 * Copyright (c) 2015-2015 Vladimir Schneider <vladimir.schneider@gmail.com>, all rights reserved.
 *
 * This code is private property of the copyright holder and cannot be used without
 * having obtained a license or prior written permission of the of the copyright holder.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package com.vladsch.idea.multimarkdown.util

import kotlin.text.Regex
import kotlin.text.RegexOption

class GitHubLinkMatcher(val projectResolver: LinkResolver.ProjectResolver, val linkRef: LinkRef, val wantLooseMatch: Boolean = false, val wantCompletionMatch: Boolean = false) {
    val subDirPattern = "(?:.+/)?"
    var gitHubLinks = false;
    var fixedPrefix = ""
        private set

    private fun matchExt(ext: String?): String {
        return matchPathText(ext.prefixWith('.'))
    }

    protected fun extensionPattern(useDefaultExt: Boolean, addAnchorExt: Boolean, isOptional: Boolean): String {
        val typeExtensions = if (useDefaultExt) linkRef.linkExtensions else arrayOf()
        var hadExtension = false
        var extensionPattern = ""
        var linkRefExt = if (!linkRef.filePath.isEmpty()) if (!linkRef.hasExt) "" else linkRef.ext else linkRef.containingFile.ext
        val anchorPattern = if (addAnchorExt && linkRef.hasAnchor) matchPathText(linkRef.anchorText, true) else ""

        for (ext in typeExtensions) {
            if (!extensionPattern.isEmpty()) extensionPattern += "|"
            if (!ext.isEmpty()) {
                extensionPattern += matchExt(ext)
                if (ext.equals(linkRefExt, ignoreCase = true)) hadExtension = true
            }
        }

        if (!hadExtension && !linkRefExt.isEmpty()) {
            if (!extensionPattern.isEmpty()) extensionPattern += "|"
            extensionPattern += matchExt(linkRefExt)
        }

        if (anchorPattern.isNotEmpty()) {
            if (!extensionPattern.isEmpty()) extensionPattern += "|"
            extensionPattern += anchorPattern
        }

        if (extensionPattern.isNotEmpty()) {
            extensionPattern = "(?:$extensionPattern)"
            if (isOptional) extensionPattern + "?"
        }

        return extensionPattern
    }

    fun matchPathText(pathText: String?, isOptional: Boolean = false, emptyMatchesAll: Boolean = false): String {
        if (pathText == null || pathText.isEmpty()) return if (emptyMatchesAll) "(?:.*)" else ""

        val suffix = if (isOptional) "\\E)?" else "\\E"
        val prefix = if (isOptional) "(?:\\Q" else "\\Q"
        return when {
            linkRef is WikiLinkRef -> prefix + pathText.replace("-| ".toRegex(), "\\\\E(?:-| )\\\\Q") + suffix
            else -> prefix + pathText.urlDecode() + suffix
        }
    }

    fun patternRegex(looseMatch: Boolean = wantLooseMatch, completionMatch: Boolean = wantCompletionMatch): Regex? {
        return patternText(looseMatch)?.toRegex(RegexOption.IGNORE_CASE)
    }

    fun patternText(looseMatch: Boolean = wantLooseMatch, completionMatch: Boolean = wantCompletionMatch): String? {
        // return a regex that will match most loosely a file path to be used by this link
        val vcsProjectBasePath = PathInfo.cleanFullPath(with(projectResolver.vcsProjectBasePath(linkRef.containingFile)) { if (this == null || this.isEmpty()) return projectResolver.projectBasePath else this } ).suffixWith('/')
        assert(!vcsProjectBasePath.isEmpty(), { "vcsProjectBasePath cannot be empty" })

        val useLooseMatch = looseMatch or completionMatch
        val useCompletionMatch = completionMatch

        val pattern: String

        // we always match subdirectories for markdown and wiki's, even for exact match since if the destination is a wiki page then no directories will be used
        // image target types have no pattern subdirectories but use exact type
        if (linkRef is WikiLinkRef) {
            // spaces match - and spaces, all subdirectories under Wiki Home match, only WIKI targets accepted, no case sensitivity
            if (!useLooseMatch && (linkRef.filePath.isEmpty() || !linkRef.path.isEmpty() || !linkRef.containingFile.isWikiPage)) return null

            val filenamePattern =
                    if (useCompletionMatch) {
                        matchPathText("", false, true)
                    } else {
                        matchPathText(if (!linkRef.filePath.isEmpty()) linkRef.fileNameNoExt else if (!linkRef.hasAnchor) "" else linkRef.containingFile.fileNameNoExt, emptyMatchesAll = !linkRef.hasAnchor)
                    }

            val anchorPattern = matchPathText(linkRef.anchorText, isOptional = true)
            val extensionPattern = extensionPattern(useDefaultExt = useLooseMatch || !linkRef.hasExt, addAnchorExt = true, isOptional = true)

            fixedPrefix = linkRef.containingFile.wikiDir.suffixWith('/')
            pattern = "^" + matchPathText(fixedPrefix) + (if (useLooseMatch || !linkRef.hasExt) subDirPattern else "") + filenamePattern + anchorPattern + extensionPattern + "$"
        } else {
            // it is assumed that if there is a wiki directory then it is a sub dir of the vcsProjectBasePath with the same name and .wiki appended
            // so if we encounter vcsProjectBasePath/wiki will will change it to vcsProjectBasePath/projectBasePathName.wiki
            // going below the vcsProjectBasePath is not supported for now.

            // if the page is a wiki home page then it will be treated as if it is located in the vcsProjectBasePath so that its relative links resolve correctly
            // however, this must be done for image links but is optional for non-image explicit links which resolve if the page was under the wiki directory
            // if it is a wiki but not the main page or not image link then its prefix is not changed

            var repoPrefixPath = vcsProjectBasePath + "blob/master/"
            var repoPrefixPathPattern = ("^\\Q" + vcsProjectBasePath + "blob/\\E[^/]+\\Q/\\E").toRegex()
            var wikiPrefixPath = vcsProjectBasePath + "wiki/"
            var prefixPath: String
            var homePageWikiPrefixPath = vcsProjectBasePath
            var filenamePattern =
                    if (useCompletionMatch) {
                        matchPathText("", false, true)
                    } else {
                        if (linkRef.filePath.isEmpty()) matchPathText(linkRef.containingFile.fileNameNoExt) else matchPathText(linkRef.fileNameNoExt)
                    }

            if (linkRef.containingFile.isWikiPage) {
                // wiki repo, files here can be accessed in two ways:
                // 1. markdown without extension, all files are logically located in the root of the wiki regardless of their physical location in the repo and will be rendered to HTML,
                // with one exception for the Home page which is logically located in the main repo directory and wiki/ is aliased to it

                // 2. any file with extension, all files are located relative to their physical location under the wiki repo

                // TODO: factor out this kind of logic into the GitHubLinkResolver it is really specific to GitHub wikis
                if (linkRef.containingFile.isWikiHomePage && (linkRef is ImageLinkRef || (linkRef.hasExt && /*!linkRef.isMarkdownExt &&*/ linkRef.path.startsWith("wiki/")))) {
                    // if the link winds up in the same directory as the homePageWikiPrefixPath, without the wiki prefix then it will not resolve
                    prefixPath = PathInfo.appendParts(homePageWikiPrefixPath, linkRef.path).filePath.suffixWith('/')
                } else {
                    prefixPath = PathInfo.appendParts(wikiPrefixPath, linkRef.path).filePath.suffixWith('/')
                    if (useLooseMatch) {
                        // correct for unnecessary wiki/
                        if (prefixPath.startsWith(wikiPrefixPath + "wiki/")) {
                            prefixPath = wikiPrefixPath + prefixPath.substring((wikiPrefixPath + "wiki/").length)
                        }
                    }
                }
            } else {
                // main repo

                // files in the main repo are logically two dirs down blob/branchOrTag/fileName..., files in the repo require no backing out, wiki, pulls, issues, ... do
                // to figure out whether the link is trying for GitHub specifics located at the repo root we will normalize the linkRef path from vcsProjectBasePath/blob/master/
                // if after normalization we still have that prefix then the link is not going for Wiki or GitHub specifics and we can just search normal files and remap
                // them later

                // if the prefix changes to vcsProjectBasePath/wiki then we will search for Wiki pages ignoring subdirectories if the link has no extension and keep subdirectories
                // if the link has an extension because in the latter case it will map to a raw markdown or image in the wiki repo
                prefixPath = PathInfo.appendParts(repoPrefixPath, linkRef.path).filePath.suffixWith('/')

                // if the file name is wiki then put back the wiki
                if (prefixPath.equals(vcsProjectBasePath) && linkRef.fileNameNoExt.equals("wiki", ignoreCase = useLooseMatch)) {
                    prefixPath += "wiki/"
                    if (!useCompletionMatch) filenamePattern = matchPathText("Home")
                }
            }

            var wikiPages = false
            gitHubLinks = false

            assert(prefixPath.endsWith('/'))

            if (prefixPath.startsWith(wikiPrefixPath)) {
                // not going for main repo or links, linking a file in the Wiki Repo
                // if not image and no extension then will match markdown files, ie. WikiPages
                wikiPages = linkRef !is ImageLinkRef && !linkRef.hasExt

                if (wikiPages && !useLooseMatch) {
                    // if there is a subdirectory in the link ref then it should not match anything, unless useLooseMatch is on
                    if (!prefixPath.equals(wikiPrefixPath)) return null
                }

                // change it to our physical wiki directory which is right after our project base with same name and .wiki extension
                var pathInfo = PathInfo(vcsProjectBasePath)

                // add subdirectories if not wiki page type search, ie. not image or link has extension
                prefixPath = pathInfo.append(pathInfo.fileNameNoExt + PathInfo.WIKI_HOME_EXTENSION).filePath.suffixWith('/') + prefixPath.substring(wikiPrefixPath.length)
            } else if (prefixPath.startsWith(repoPrefixPath)) {
                // linking to files in the main repo, in the master branch
                prefixPath = vcsProjectBasePath + prefixPath.substring(repoPrefixPath.length)
            } else if (prefixPath.matches(repoPrefixPathPattern)) {
                // linking to files in the main repo, under some existing or non-existing branch or tag
                val match = repoPrefixPathPattern.find(prefixPath)
                if (match != null) prefixPath = vcsProjectBasePath + prefixPath.substring(match.range.end + 1)
            } else {
                // if in the main project directory then can link to issues, pulls, etc
                // otherwise somewhere before the main repo or in between and nothing will be found
                if (useLooseMatch) {
                    // correct for blob/ when blob/master/ should be there
                    if (!prefixPath.suffixWith('/').equals(vcsProjectBasePath, true)) {
                        if (!prefixPath.suffixWith('/').equals(vcsProjectBasePath + "blob/", true)) return null
                        prefixPath = vcsProjectBasePath
                    }
                } else {
                    if (!prefixPath.suffixWith('/').equals(vcsProjectBasePath)) return null
                }

                // looking for GitHub Links, linkRef name should be one of the GitHub link directories, without wiki since that is taken care of separately
                // already vcsProjectBasePath
                // TODO: implement resolution to hard-coded links so that no extra code needs to be handled
                if (!linkRef.filePath.isEmpty() && linkRef.fileName !in GitHubLinkResolver.GITHUB_LINKS) return null
                gitHubLinks = true
            }

            var extensionPattern = ""
            var anchorPattern = ""

            if (wikiPages) {
                anchorPattern = matchPathText(linkRef.anchorText, true)
                extensionPattern = extensionPattern (useDefaultExt = true, addAnchorExt = true, isOptional = true)
            } else {
                // prefix is the file's directory plus any path in the link itself, loose match will search down into the tree, but not up
                // also useLooseMatch is not particular about extension as long as there is one that is an image extension
                // the file name has to match, no anchor option is used
                if (useLooseMatch || linkRef.hasExt || linkRef.filePath.isEmpty()) extensionPattern = extensionPattern(useDefaultExt = useLooseMatch, addAnchorExt = false, isOptional = false)
            }

            fixedPrefix = prefixPath.suffixWith('/')
            pattern = "^" + matchPathText(fixedPrefix) + (if (wikiPages || useLooseMatch && !linkRef.filePath.isEmpty()) subDirPattern else "") + filenamePattern + anchorPattern + extensionPattern + "$"
        }

        return pattern
    }
}