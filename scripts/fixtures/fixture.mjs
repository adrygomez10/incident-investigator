// Deterministic transport fixture. Does not contact an AI provider, Sentry or GitHub.
import http from 'node:http';
import readline from 'node:readline';
import fs from 'node:fs';
const mode=process.argv[2];
const field={type:'string'};
const schema=(properties,required=[])=>({type:'object',properties:Object.fromEntries(properties.map(p=>[p,field])),required,additionalProperties:false});
const revision='0123456789abcdef0123456789abcdef01234567'; // Explicitly synthetic, not a repository commit.
const sourcePath='src/main/java/dev/adriangm/orders/domain/CustomerReference.java';
const file={name:'get_file_contents',description:'[TEST FIXTURE] Read source file',inputSchema:schema(['owner','repo','path','ref'],['owner','repo','path','ref'])};
const search={name:'search_issues',description:'[TEST FIXTURE] Search issues',inputSchema:schema(['organizationSlug','projectSlugOrId','query','regionUrl'],['organizationSlug'])};
const write={name:'issue_write',description:'[TEST FIXTURE] Write issue',inputSchema:schema(['method','owner','repo','title','body'],['method','owner','repo','title','body'])};
const listing={name:'list_issues',description:'[TEST FIXTURE] List receipts',inputSchema:{type:'object',properties:{owner:field,repo:field,perPage:{type:'integer'},after:field},required:['owner','repo']}};
if(mode==='model') {
  http.createServer(async(req,res)=>{
    let data='';for await(const chunk of req)data+=chunk;
    const body=JSON.parse(data);const previous=body.messages.filter(m=>m.role==='tool');
    let name,args;
    if(previous.length===0){name='sentry__search_issues';args={query:'level:error'};}
    else if(previous.length===1){name='github__get_file_contents';args={path:sourcePath,ref:revision};}
    else if(previous.length===2){name='search_knowledge';args={query:'customerReference'};}
    else {
      const passages=JSON.parse(previous[2].content);const ragId=passages[0]?.evidenceId;
      name='finish_investigation';args={facts:['[TEST FIXTURE] Error observed [E1]','[TEST FIXTURE] Source calls strip without checking for null [E2]',...(ragId?[`The reference is optional [${ragId}]`]:[])],hypotheses:['The normalization may explain requests without a reference'],uncertainties:['No real model or external services were evaluated','No previous deployment was supplied'],recommendedChecks:['Send the same checkout requests with and without customerReference'],evidenceIds:['E1','E2',...(ragId?[ragId]:[])],title:'[TEST FIXTURE] Orders fail without a reference',body:'[TEST FIXTURE] Compare error [E1] and source [E2] with the optional-field contract.'};
    }
    const output={choices:[{finish_reason:'tool_calls',message:{role:'assistant',content:'',tool_calls:[{id:'call-'+previous.length,type:'function',function:{name,arguments:JSON.stringify(args)}}]}}],usage:{prompt_tokens:100,completion_tokens:50}};
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(output));
  }).listen(18094,'0.0.0.0');
} else {
  const github=mode==='github';const input=readline.createInterface({input:process.stdin});
  const receiptsPath='/fixture-state/receipts.json';
  const receipts=()=>fs.existsSync(receiptsPath)?JSON.parse(fs.readFileSync(receiptsPath,'utf8')):[];
  const text=value=>({content:[{type:'text',text:JSON.stringify(value)}],isError:false});
  input.on('line',line=>{
    const req=JSON.parse(line);if(req.id===undefined)return;
    fs.appendFileSync('/fixture-state/mcp-wire.jsonl',JSON.stringify({server:github?'github':'sentry',direction:'request',message:req})+'\n');
    let result;const params=req.params??{};
    if(req.method==='initialize')result={protocolVersion:params.protocolVersion,capabilities:{tools:{}},serverInfo:{name:'explicit-test-fixture',version:'1.0'}};
    else if(req.method==='tools/list')result={tools:github?[file,write,listing]:[search]};
    else if(req.method==='tools/call') {
      if(params.name==='issue_write') {
        const saved=receipts();const number=saved.length+1;const item={...params.arguments,number,html_url:`https://github.com/adrygomez10/orders-incident-demo/issues/${number}`};saved.push(item);fs.writeFileSync(receiptsPath,JSON.stringify(saved));
        if(item.body.includes('UNCERTAIN_TEST'))return; // Persist the receipt, then deliberately lose the response.
        result=text(item);
      } else if(params.name==='list_issues')result=text({issues:receipts(),pageInfo:{hasNextPage:false}});
      else result=text(github?{kind:'TEST FIXTURE',ref:params.arguments.ref,path:params.arguments.path,content:'public CustomerReference { value = value.strip(); }',url:`https://github.com/adrygomez10/orders-incident-demo/blob/${params.arguments.ref}/${params.arguments.path}`}:{kind:'TEST FIXTURE',issueId:'ORDERS-INCIDENT-DEMO-1',exception:'NullPointerException in CustomerReference.<init>',release:'orders-demo@'+revision,url:'https://sentry.io/organizations/test-only/issues/1/'});
    } else result={};
    const message={jsonrpc:'2.0',id:req.id,result};
    fs.appendFileSync('/fixture-state/mcp-wire.jsonl',JSON.stringify({server:github?'github':'sentry',direction:'response',message})+'\n');
    process.stdout.write(JSON.stringify(message)+'\n');
  });
}
