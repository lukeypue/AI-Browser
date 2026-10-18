import java.nio.charset.StandardCharsets;
import java.util.*;
import com.appgate.brain.json.*;
import com.appgate.brain.model.*;
import com.appgate.brain.perception.SpsParser;
import com.appgate.brain.verify.Verifier;
import com.appgate.brain.engine.LearningOpportunities;
import com.appgate.brain.engine.LearningTarget;
import com.appgate.brain.skills.BuiltinSkills;

/** Test-only source launcher. Input is synthetic content.js observations, never phone data. */
class AndroidFixtureSemantics {
 public static void main(String[] args) throws Exception {
  JsonObject input=Json.INSTANCE.parseObject(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
  SpsParser parser=new SpsParser();
  SemanticPageState before=parser.parse(input.optObject("before"),0L);
  SemanticPageState after=parser.parse(input.optObject("after"),1000L);
  String mode=input.optString("mode","");
  Postcondition post=switch(mode) {
   case "expand" -> Postcondition.TextExpanded.INSTANCE;
   case "new_results", "next" -> Postcondition.NewResults.INSTANCE;
   case "dialog" -> Postcondition.DialogClosed.INSTANCE;
   case "sort" -> BuiltinSkills.INSTANCE.getSortResults().getPost().get(0);
   case "condition" -> new Postcondition.ConstraintApplied("condition","Good");
   default -> throw new IllegalArgumentException("unknown synthetic proof mode");
  };
  Action action=new Action(ActionKind.CLICK,null,null,null,null,false,List.of(post),EffectClass.READ,null,"synthetic fixture proof");
  boolean holds=Verifier.INSTANCE.holds(post,action,before,after,Set.of(),new ArrayList<>());
  JsonObject output=new JsonObject();
  output.put("holds",holds);
  String lesson=switch(mode){case "expand"->"expand_description";case "condition"->"select_facet";case "sort"->"sort_results";case "next"->"next_page";case "dialog"->"dismiss_dialog";default->"scroll_results";};
  LearningTarget target=LearningOpportunities.INSTANCE.target(before,lesson);
  output.put("target_skill",target==null?"":target.getSkillId());
  output.put("target_value",target==null?"":target.getParams().getOrDefault("value",""));
  output.put("target_order",target==null?"":target.getParams().getOrDefault("order",""));
  output.put("expand_role",before.has(Role.EXPAND_TEXT));
  output.put("next_role",before.has(Role.PAGE_NEXT));
  output.put("dialog_role",before.getRegions().stream().anyMatch(region -> region.getRole() == RegionRole.DIALOG));
  output.put("close_role",before.has(Role.CLOSE));
  output.put("condition_facet",before.facet("condition")!=null);
  output.put("text_delta",after.getTextLength()-before.getTextLength());
  output.put("detail_delta",after.getDetailText().length()-before.getDetailText().length());
  output.putStrings("before_keys",before.getResultKeys());
  output.putStrings("after_keys",after.getResultKeys());
  System.out.println(output);
 }
}
