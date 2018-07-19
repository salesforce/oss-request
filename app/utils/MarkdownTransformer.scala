/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import laika.api.Transform
import laika.parse.markdown.Markdown
import laika.render.HTML

object MarkdownTransformer {

  // todo: server-side url to link
  def transform(contents: String): String = {
    Transform.from(Markdown).to(HTML).fromString(contents).toString()
  }

}
