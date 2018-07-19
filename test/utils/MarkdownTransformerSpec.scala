/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import org.scalatest.{MustMatchers, WordSpec}

class MarkdownTransformerSpec extends WordSpec with MustMatchers {

  "transform" must {
    "convert markdown to html" in {
      MarkdownTransformer.transform("[asdf](http://asdf.com)") must equal ("""<p><a href="http://asdf.com">asdf</a></p>""")
    }
  }

}


